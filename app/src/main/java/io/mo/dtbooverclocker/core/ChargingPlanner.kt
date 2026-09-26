package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.*

/** Validates a whole form before applying any operation; the snapshot prevents stale writes. */
object ChargingPlanner {
    data class Preview(val values: Map<String, Long>, val changes: List<String>)
    data class Plan(val replayedText: String, val transaction: DeviceTreeTransaction)

    fun preview(node: ChargingNode, inputs: Map<String, String>): Preview {
        val fields = node.fields.associateBy { it.inputKey }
        require(inputs.isNotEmpty()) { "没有可修改的充电参数" }
        val values = inputs.mapValues { (name, input) ->
            val field = requireNotNull(fields[name]) { "不支持的充电属性：$name" }
            require(field.issue == null) { "$name：${field.issue}" }
            // Existing out-of-range vendor sentinels are retained unless explicitly edited.
            if (input == ChargingAnalyzer.displayValue(field)) requireNotNull(field.value)
            else ChargingAnalyzer.parseInput(field.parameter, input)
        }
        val changed = values.filter { (name, value) -> value != fields.getValue(name).value }
        val merged = fields.mapValues { (name, field) -> values[name] ?: field.value }
        node.fields.filter { it.cellIndex != null && it.parameter.descendingStride > 0 }.groupBy { it.parameter.name }.forEach { (_, table) ->
            if (table.any { it.inputKey in changed }) {
                val levels = table.sortedBy { it.cellIndex }.map { requireNotNull(merged[it.inputKey]) }
                val stride = table.first().parameter.descendingStride
                require((stride until levels.size).all { levels[it - stride] >= levels[it] }) {
                    "温控限流必须按档位非递增排列，同一通道的后一档不能大于前一档"
                }
            }
        }
        fun below(lower: String, upper: String, message: String) {
            if (lower !in changed && upper !in changed) return
            val low = merged[lower] ?: return
            val high = merged[upper] ?: return
            require(low <= high) { message }
        }
        below("precharge-current-microamp", "constant-charge-current-max-microamp", "预充电电流不能超过恒流充电电流上限")
        below("min_vbat", "max_vbat", "启动电压下限不能超过电压上限")
        below("recharge_vbat", "max_vbat", "重新充电电压不能超过电压上限")
        below("charge-term-current-microamp", "constant-charge-current-max-microamp", "终止电流不能超过恒流充电电流上限")
        below("precharge-upper-limit-microvolt", "constant-charge-voltage-max-microvolt", "预充电电压不能超过恒压充电电压上限")
        below("constant-charge-voltage-max-microvolt", "over-voltage-threshold-microvolt", "恒压充电电压不能超过过压保护阈值")
        below("oplus,cp-open-offset-min-mv", "oplus,cp-open-offset-mv", "充电泵最小开启电压偏移不能超过开启电压偏移")
        // The Qualcomm pair uses different native units.
        if ("qcom,auto-recharge-vbat-mv" in changed || "qcom,fv-max-uv" in changed) {
            val recharge = merged["qcom,auto-recharge-vbat-mv"]
            val floatVoltage = merged["qcom,fv-max-uv"]
            require(recharge == null || floatVoltage == null || recharge * 1000 <= floatVoltage) {
                "重新充电电压不能超过浮充电压上限"
            }
        }
        if ("qcom,chg-term-ua" in changed || "qcom,fastchg-current-ma" in changed) {
            val term = merged["qcom,chg-term-ua"]
            val fast = merged["qcom,fastchg-current-ma"]
            require(term == null || fast == null || term <= fast * 1000) { "终止电流不能超过最大快充电流" }
        }
        val changes = changed.map { (name, value) ->
            val field = fields.getValue(name)
            fun label(v: Long): String = if (field.parameter.boolean) { if (v == 1L) "开启" else "关闭" }
                else "${ChargingAnalyzer.displayValue(field.copy(value = v))} ${field.parameter.unit}"
            "${field.parameter.label}：${label(requireNotNull(field.value))} → ${label(value)}"
        }
        return Preview(changed, changes)
    }

    fun plan(text: String, snapshot: ChargingNode, inputs: Map<String, String>): Plan {
        val current = ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(snapshot.entryIndex, text)))
            .firstOrNull { it.nodePath == snapshot.nodePath }
        require(current == snapshot) { "充电节点已变化，请重新扫描后编辑" }
        val preview = preview(snapshot, inputs)
        require(preview.values.isNotEmpty()) { "充电参数没有变化" }
        val fields = snapshot.fields.associateBy { it.inputKey }
        val operations = preview.values.keys.groupBy { fields.getValue(it).parameter.name }.map { (name, keys) ->
            val field = fields.getValue(keys.first())
            val value = preview.values.getValue(keys.first())
            when {
                field.cellIndex != null -> {
                    val cells = requireNotNull(DtsNumericValueCodec.decodeCells(field.rawValue)).toMutableList()
                    keys.forEach { key -> cells[requireNotNull(fields.getValue(key).cellIndex)] = preview.values.getValue(key) }
                    SetPropertyChange(snapshot.entryIndex, snapshot.nodePath, name, field.rawValue,
                        DtsNumericValueCodec.encodeCellsLike(field.rawValue, cells))
                }
                field.parameter.boolean && value == 0L -> DeletePropertyChange(snapshot.entryIndex, snapshot.nodePath, name, field.rawValue)
                field.parameter.boolean -> AddPropertyChange(snapshot.entryIndex, snapshot.nodePath, name, null)
                else -> SetPropertyChange(snapshot.entryIndex, snapshot.nodePath, name, field.rawValue,
                    DtsNumericValueCodec.encodeU32Like(field.rawValue, value))
            }
        }
        val replayed = operations.fold(text, DeviceTreeEditor::apply)
        val updated = requireNotNull(DeviceTreeParser.parse(snapshot.entryIndex, replayed).findNode(snapshot.nodePath))
        preview.values.forEach { (key, value) ->
            val field = fields.getValue(key)
            val property = updated.properties.firstOrNull { it.name == field.parameter.name }
            val actual = when {
                field.cellIndex != null -> DtsNumericValueCodec.decodeCells(property?.rawValue)?.getOrNull(field.cellIndex)
                field.parameter.boolean -> if (property == null) 0L else 1L
                else -> DtsNumericValueCodec.decodeU32(property?.rawValue)
            }
            check(actual == value) { "充电参数写入校验失败：$key" }
        }
        val staged = ModuleStagedChange(
            module = FeatureModuleKind.CHARGING, entryIndex = snapshot.entryIndex,
            affectedNodePaths = listOf(snapshot.nodePath),
            summary = "Charging · DTB ${snapshot.entryIndex} · ${snapshot.nodePath.substringAfterLast('/')} · ${operations.size} 项修改",
            changes = preview.changes,
            warnings = listOf("充电参数实际生效值取决于电池、充电芯片和驱动限制。")
        )
        return Plan(replayed, DeviceTreeTransaction.charging(staged, operations))
    }
}
