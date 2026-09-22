package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.*
import java.math.BigDecimal

/** Explicit binding names prevent confusing register addresses, phandles and charging limits.
 * Binding sources and supported scope: docs/charging.md.
 */
object ChargingAnalyzer {
    private fun ua(name: String, label: String) = ChargingParameter(name, label, "mA", "µA", 1000)
    private fun uv(name: String, label: String) = ChargingParameter(name, label, "mV", "µV", 1000)

    val parameters = listOf(
        ua("qcom,fcc-max-ua", "最大快充电流"),
        uv("qcom,fv-max-uv", "浮充电压上限"),
        ua("qcom,usb-icl-ua", "USB 输入电流上限"),
        ua("qcom,dc-icl-ua", "DC 输入电流上限"),
        ua("qcom,usb-ocl-ua", "OTG 输出电流上限"),
        ua("qcom,wls-current-max-ua", "无线充电电流上限"),
        ua("qcom,boost-threshold-ua", "升压电流阈值"),
        ChargingParameter("qcom,fastchg-current-ma", "最大快充电流", "mA"),
        ChargingParameter("qcom,float-voltage-mv", "浮充电压", "mV"),
        uv("qcom,max-voltage-uv", "电池额定电压上限"),
        ua("qcom,chg-term-ua", "电池充电终止电流"),
        ChargingParameter("qcom,auto-recharge-soc", "重新充电电量阈值", "%", minimum = 0, maximum = 100),
        ChargingParameter("qcom,auto-recharge-vbat-mv", "重新充电电池电压", "mV"),
        ChargingParameter("qcom,chg-inhibit-threshold-mv", "充电抑制电压差", "mV", allowedValues = setOf(50, 100, 200, 300)),
        ua("constant-charge-current-max-microamp", "恒流充电电流上限"),
        uv("constant-charge-voltage-max-microvolt", "恒压充电电压上限"),
        ua("precharge-current-microamp", "预充电电流"),
        uv("precharge-upper-limit-microvolt", "预充电电压上限"),
        ua("charge-term-current-microamp", "充电终止电流"),
        uv("re-charge-voltage-microvolt", "重新充电电压差"),
        uv("over-voltage-threshold-microvolt", "过压保护阈值"),
        ChargingParameter("qcom,hvdcp-disable", "禁用 HVDCP 快充", "", minimum = 0, maximum = 1, boolean = true),
        ChargingParameter("qcom,usb-pd-disable", "禁用 USB PD", "", minimum = 0, maximum = 1, boolean = true)
    )
    private val byName = parameters.associateBy { it.name }
    private const val THERMAL_TABLE = "qcom,thermal-mitigation"
    private val tokens = listOf("charger", "charging", "fastchg", "battery", "smb2", "smb5", "smb135", "smb138", "float-voltage", "fcc-max")

    fun analyze(documents: List<DeviceTreeDocument>): List<ChargingNode> = documents.flatMap { document ->
        val targets = overlayTargets(document)
        document.flatten().mapNotNull nodeLoop@{ node ->
            // Overlay metadata may repeat charging property names but stores relocation offsets.
            if (node.path.split('/').any { it in setOf("__symbols__", "__fixups__", "__local_fixups__") }) return@nodeLoop null
            val compatible = node.properties.firstOrNull { it.name == "compatible" }?.rawValue
            val targetLabel = targets[node.path]
            val mcaFields = McaChargingBindings.fields(node, compatible)
            val searchable = (listOf(node.name, compatible.orEmpty(), targetLabel.orEmpty()) + node.properties.map { it.name }).joinToString(" ").lowercase()
            if (mcaFields.isEmpty() && node.properties.none { it.name in byName || it.name == THERMAL_TABLE } && tokens.none(searchable::contains)) return@nodeLoop null
            val fields = parameters.mapNotNull { parameter ->
                val matches = node.properties.filter { it.name == parameter.name }
                val property = matches.firstOrNull()
                // Only this verified binding permits synthesizing absent protocol flags.
                val smb5 = compatible?.split('"')?.contains("qcom,qpnp-smb5") == true
                if (property == null && !(parameter.boolean && smb5)) return@mapNotNull null
                val value = if (parameter.boolean) {
                    if (property == null) 0L else if (property.rawValue == null) 1L else null
                } else DtsNumericValueCodec.decodeU32(property?.rawValue)
                ChargingField(parameter, property != null, property?.rawValue, value, when {
                    matches.size > 1 -> "属性重复，需先在设备树中修复"
                    value == null -> if (parameter.boolean) "此开关不是空布尔属性" else "不是单个 32 位数值，保留原始内容"
                    else -> null
                })
            } + thermalFields(node, compatible, targetLabel) + mcaFields
            ChargingNode(document.entryIndex, node.path, compatible,
                node.properties.firstOrNull { it.name == "status" }?.rawValue?.removeSurrounding("\""),
                fields, node.properties.filter { property -> fields.none { it.parameter.name == property.name } }.map { it.name to it.rawValue }, targetLabel)
        }
    }

    /** Resolve only fragment target relocations, never treat __fixups__ offsets as parameters.
     * DTC emits string lists both as separate strings and as a single string with embedded NULs.
     */
    private fun overlayTargets(document: DeviceTreeDocument): Map<String, String> {
        val labels = mutableMapOf<String, MutableSet<String>>()
        document.findNode("/__fixups__")?.properties?.forEach { property ->
            Regex("\"([^\"]*)\"").findAll(property.rawValue.orEmpty()).flatMap { match ->
                match.groupValues[1].replace(Regex("\\\\(?:x00|0{1,3})"), "\u0000").split('\u0000').asSequence()
            }.forEach descriptorLoop@{ descriptor ->
                val match = Regex("^(/[^:]+):target:0$").matchEntire(descriptor) ?: return@descriptorLoop
                val fragment = document.findNode(match.groupValues[1]) ?: return@descriptorLoop
                if (fragment.properties.count { it.name == "target" } != 1) return@descriptorLoop
                val raw = fragment.properties.first { it.name == "target" }.rawValue
                if (DtsNumericValueCodec.decodeU32(raw) != 0xffffffffL) return@descriptorLoop
                val path = "${fragment.path}/__overlay__"
                if (document.findNode(path) != null) labels.getOrPut(path) { mutableSetOf() }.add(property.name)
            }
        }
        return labels.mapNotNull { (path, targets) -> targets.singleOrNull()?.let { path to it } }.toMap()
    }

    private fun thermalFields(node: DeviceTreeNode, compatible: String?, targetLabel: String?): List<ChargingField> {
        val properties = node.properties.filter { it.name == THERMAL_TABLE }
        val property = properties.firstOrNull() ?: return emptyList()
        // The same property has different units across charger drivers. Do not infer from magnitude.
        val compatibles = compatible?.split('"').orEmpty()
        val glink = "qcom,battery-charger" in compatibles ||
            (compatible == null && targetLabel == "battery_charger" && node.properties.any { it.name == "qcom,wireless-fw-name" })
        val parameter = ChargingParameter(THERMAL_TABLE, "充电温控限流", "mA", "µA", 1000, minimum = 0, descendingStride = 1)
        val values = DtsNumericValueCodec.decodeCells(property.rawValue)
        val issue = when {
            properties.size != 1 -> "属性重复，需先在设备树中修复"
            !glink -> "无法确认充电驱动和数组单位，保留原始内容"
            values.isNullOrEmpty() -> "不是有效的 U32 电流档位数组，保留原始内容"
            else -> null
        }
        if (issue != null) return listOf(ChargingField(parameter, true, property.rawValue, null, issue))
        return requireNotNull(values).mapIndexed { index, value ->
            ChargingField(parameter.copy(label = "温控限流 · 第 ${index + 1} 档"), true, property.rawValue, value, cellIndex = index)
        }
    }

    fun displayValue(field: ChargingField): String = field.value?.let {
        if (field.parameter.boolean) (it == 1L).toString()
        else BigDecimal.valueOf(it).divide(BigDecimal.valueOf(field.parameter.scale)).stripTrailingZeros().toPlainString()
    }.orEmpty()

    fun parseInput(parameter: ChargingParameter, input: String): Long {
        if (parameter.boolean) return when (input) {
            "true" -> 1L
            "false" -> 0L
            else -> throw IllegalArgumentException("请选择有效的开关状态")
        }
        val text = input.trim()
        require(Regex("[0-9]+(?:\\.[0-9]+)?").matches(text) && text.length <= 24) { "请输入有效的非负十进制数值" }
        val raw = try {
            BigDecimal(text).multiply(BigDecimal.valueOf(parameter.scale)).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("精度不能小于 1 ${parameter.rawUnit}，且数值不能溢出")
        }
        require(raw in parameter.minimum..parameter.maximum) {
            "允许范围 ${BigDecimal.valueOf(parameter.minimum).divide(BigDecimal.valueOf(parameter.scale)).stripTrailingZeros().toPlainString()}～${BigDecimal.valueOf(parameter.maximum).divide(BigDecimal.valueOf(parameter.scale)).stripTrailingZeros().toPlainString()} ${parameter.unit}"
        }
        require(parameter.allowedValues.isEmpty() || raw in parameter.allowedValues) {
            "仅支持 ${parameter.allowedValues.joinToString(" / ")} ${parameter.unit}"
        }
        return raw
    }
}
