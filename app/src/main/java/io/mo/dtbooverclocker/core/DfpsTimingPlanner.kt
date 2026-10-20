package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.TimingCandidate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * QCOM DFPS 面板的刷新率扩充。
 *
 * 视频模式面板声明 `qcom,mdss-dsi-pan-enable-dynamic-fps` 后，内核（dsi_display_get_modes_helper）
 * 把每个时序节点按 `qcom,dsi-supported-dfps-list` 展开成多个模式，模式刷新率取列表值，DSI 时钟固定为
 * 时序节点的 `qcom,mdss-dsi-panel-clockrate`：
 * - `dfps_immediate_porch_mode_both_hv_porch`：第 i 个模式的六个 porch 直接取 `qcom,dsi-dfps-*-list[i]`；
 * - `dfps_immediate_porch_mode_vfp`：由基准时序按 `vfp_new = vfp ± vtotal * |fps0 - fps| / fps` 推算。
 * 因此改时序节点的 framerate 不会产生新模式。新增档位必须写入 DFPS 列表；超过原厂最高刷新率时，
 * 固定时钟下帧长已到下限，只能按比例提高时钟，并同步加大其余档位的 VFP 让它们保持原刷新率。
 *
 * `qcom,dsi-supported-qsync-min-fps-list` 长度与 DFPS 列表不一致时面板初始化失败，必须同步扩充；
 * `qcom,dsi-qsync-avr-step-list` 同样按序号取值，一并处理。
 */
object DfpsTimingPlanner
{
    enum class Type { HV_PORCH, VFP }

    const val DFPS_LIST = "qcom,dsi-supported-dfps-list"
    private const val ENABLE = "qcom,mdss-dsi-pan-enable-dynamic-fps"
    private const val UPDATE_TYPE = "qcom,mdss-dsi-pan-fps-update"
    private const val CLOCK = "qcom,mdss-dsi-panel-clockrate"
    private const val FRAMERATE = "qcom,mdss-dsi-panel-framerate"
    private const val HEIGHT = "qcom,mdss-dsi-panel-height"
    private const val VFP = "qcom,mdss-dsi-v-front-porch"
    private const val VBP = "qcom,mdss-dsi-v-back-porch"
    private const val VPW = "qcom,mdss-dsi-v-pulse-width"

    private const val HFP_LIST = "qcom,dsi-dfps-hfp-list"
    private const val HBP_LIST = "qcom,dsi-dfps-hbp-list"
    private const val HPW_LIST = "qcom,dsi-dfps-hpw-list"
    private const val VBP_LIST = "qcom,dsi-dfps-vbp-list"
    private const val VFP_LIST = "qcom,dsi-dfps-vfp-list"
    private const val VPW_LIST = "qcom,dsi-dfps-vpw-list"
    private val porchLists = listOf(HFP_LIST, HBP_LIST, HPW_LIST, VBP_LIST, VFP_LIST, VPW_LIST)
    private val perFpsLists = listOf("qcom,dsi-supported-qsync-min-fps-list", "qcom,dsi-qsync-avr-step-list")

    data class Panel(
        val panelPath: String,
        val timingPath: String,
        val type: Type,
        val rates: List<Int>,
        /** 面板节点上按 DFPS 序号对齐的列表（不含 DFPS 列表本身），属性名 → 原值。 */
        val lists: Map<String, List<Long>>,
        val baseHz: Int,
        val clockHz: Long?,
        val vActive: Int,
        val vFrontPorch: Int,
        val vBackPorch: Int,
        val vPulseWidth: Int,
        val hasDynamicClock: Boolean
    )
    {
        val baseVTotal: Int get() = vActive + vPulseWidth + vBackPorch + vFrontPorch

        /** 第 [index] 个 DFPS 模式在内核中的垂直总行数。 */
        fun vTotal(index: Int): Int = when (type)
        {
            Type.HV_PORCH -> vActive + list(VPW_LIST)[index].toInt() + list(VBP_LIST)[index].toInt() + list(VFP_LIST)[index].toInt()
            Type.VFP -> vActive + vPulseWidth + vBackPorch + vfpFromBase(baseHz, baseVTotal, vFrontPorch, rates[index])
        }

        fun list(name: String): List<Long> = requireNotNull(lists[name]) { "面板缺少 $name" }

        /** 不能替换的档位：与基准时序同刷新率的默认模式（开机首选模式）。 */
        val replaceableRates: List<Int> get() = rates.filter { it != baseHz }
    }

    data class Calculation(
        val targetHz: Int,
        val replaceIndex: Int?,
        val referenceHz: Int,
        val clockHz: Long?,
        val newClockHz: Long?,
        val newRates: List<Int>,
        val newLists: Map<String, List<Long>>,
        val newBaseVFrontPorch: Int,
        /** 新档位的垂直前肩（HV 模式取自列表，VFP 模式为内核推算值）。 */
        val targetVFrontPorch: Int,
        val changes: List<String>,
        val warnings: List<String>
    )
    {
        val clockIncreasePercent: Double?
            get() = if (clockHz != null && newClockHz != null && clockHz > 0) (newClockHz - clockHz) * 100.0 / clockHz else null
    }

    fun readPanel(document: DeviceTreeDocument, candidate: TimingCandidate): Panel
    {
        val timing = requireNotNull(document.findNode(candidate.nodePath)) { "DTS 中找不到时序节点：${candidate.nodePath}" }
        val timings = requireNotNull(document.findNode(parentOf(timing.path))) { "找不到时序节点的父级" }
        require(timings.name.endsWith("display-timings")) { "时序节点不在 display-timings 下，无法定位面板" }
        val panel = requireNotNull(document.findNode(parentOf(timings.path))) { "找不到面板节点" }

        require(panel.properties.any { it.name == ENABLE }) { "该面板未启用 DFPS" }
        require(raw(panel, "qcom,mdss-dsi-panel-type")?.contains("dsi_cmd_mode") != true) { "命令模式面板内核不走 DFPS" }
        val type = when (val update = raw(panel, UPDATE_TYPE)?.trim('"', ' '))
        {
            "dfps_immediate_porch_mode_both_hv_porch" -> Type.HV_PORCH
            "dfps_immediate_porch_mode_vfp" -> Type.VFP
            else -> error("暂不支持 DFPS 类型 ${update ?: "未声明"}，仅支持 porch_mode_both_hv_porch 与 porch_mode_vfp")
        }
        require(timings.children.count { child -> child.properties.any { it.name == FRAMERATE } } == 1) {
            "面板含多个时序节点，暂不支持扩充 DFPS 列表"
        }
        require(timing.children.none { it.name == "qcom,mdss-dsi-dfps-commands" }) {
            "该面板切换帧率时要发送面板命令（qcom,mdss-dsi-dfps-commands），新档位没有对应命令，暂不支持"
        }

        val rates = cells(panel, DFPS_LIST)?.map { it.toInt() }
        require(!rates.isNullOrEmpty()) { "面板缺少 $DFPS_LIST" }
        val lists = buildMap {
            if (type == Type.HV_PORCH) porchLists.forEach { name ->
                val values = requireNotNull(cells(panel, name)) { "面板缺少 $name" }
                require(values.size == rates.size) { "$name 长度 ${values.size} 与 DFPS 列表 ${rates.size} 不一致" }
                put(name, values)
            }
            perFpsLists.forEach { name ->
                val values = cells(panel, name) ?: return@forEach
                require(values.size == rates.size) { "$name 长度 ${values.size} 与 DFPS 列表 ${rates.size} 不一致" }
                put(name, values)
            }
        }

        return Panel(
            panelPath = panel.path,
            timingPath = timing.path,
            type = type,
            rates = rates,
            lists = lists,
            baseHz = requireNotNull(number(timing, FRAMERATE)).toInt(),
            clockHz = number(timing, CLOCK),
            vActive = requireNotNull(number(timing, HEIGHT)) { "时序缺少 $HEIGHT" }.toInt(),
            vFrontPorch = requireNotNull(number(timing, VFP)) { "时序缺少 $VFP" }.toInt(),
            vBackPorch = requireNotNull(number(timing, VBP)) { "时序缺少 $VBP" }.toInt(),
            vPulseWidth = requireNotNull(number(timing, VPW)) { "时序缺少 $VPW" }.toInt(),
            hasDynamicClock = panel.properties.any { it.name == "qcom,dsi-dyn-clk-enable" } ||
                timing.properties.any { it.name == "qcom,dsi-dyn-clk-list" }
        )
    }

    /**
     * @param replaceHz 非空时替换列表中该刷新率（镜像体积不变）；为空时追加到列表末尾。
     */
    fun calculate(panel: Panel, targetHz: Int, replaceHz: Int? = null): Calculation
    {
        require(targetHz in 24..360) { "目标刷新率必须在 24..360 Hz 范围内" }
        val replaceIndex = replaceHz?.let { hz ->
            require(hz != panel.baseHz) { "$hz Hz 是开机默认模式，不能替换" }
            panel.rates.indexOf(hz).also { require(it >= 0) { "DFPS 列表中没有 $hz Hz" } }
        }
        require(panel.rates.filterIndexed { i, _ -> i != replaceIndex }.none { it == targetHz }) {
            "DFPS 列表已有 $targetHz Hz"
        }

        // 以原厂最高档为参照：它的帧长已是固定时钟下的下限。
        val reference = panel.rates.indices.maxBy { panel.rates[it] }
        val referenceHz = panel.rates[reference]
        val wantedScale = maxOf(1.0, targetHz.toDouble() / referenceHz)
        val warnings = mutableListOf<String>()

        val newClock: Long?
        val scale: Double
        if (wantedScale > 1.0)
        {
            val clock = requireNotNull(panel.clockHz) {
                "时序未声明 $CLOCK，无法按比例提高 DSI 时钟"
            }
            require(!panel.hasDynamicClock) { "面板启用了动态时钟（dyn-clk），暂不支持提高 DSI 时钟" }
            newClock = (clock * wantedScale).roundToLong()
            scale = newClock.toDouble() / clock
        }
        else
        {
            newClock = panel.clockHz
            scale = 1.0
        }

        val newBaseVTotal = (panel.baseVTotal * scale).roundToInt()
        val newBaseVfp = panel.vFrontPorch + newBaseVTotal - panel.baseVTotal

        val newRates = panel.rates.toMutableList().apply {
            if (replaceIndex != null) set(replaceIndex, targetHz) else add(targetHz)
        }
        val newLists = mutableMapOf<String, List<Long>>()
        val targetVfp: Int
        when (panel.type)
        {
            Type.HV_PORCH ->
            {
                // 新档位沿用参照档位的 porch，只用 VFP 补齐帧长：vtotal = 参照 vtotal × 参照刷新率 × 缩放 / 目标刷新率。
                val referenceVTotal = panel.vTotal(reference)
                val targetVTotal = (referenceVTotal.toDouble() * referenceHz * scale / targetHz).roundToInt()
                targetVfp = targetVTotal - panel.vActive -
                    panel.list(VPW_LIST)[reference].toInt() - panel.list(VBP_LIST)[reference].toInt()
                porchLists.forEach { name ->
                    val old = panel.list(name)
                    val scaled = if (name == VFP_LIST) old.mapIndexed { i, vfp ->
                        val vTotal = panel.vTotal(i)
                        vfp + (vTotal * scale).roundToInt() - vTotal
                    } else old
                    val newValue = if (name == VFP_LIST) targetVfp.toLong() else old[reference]
                    newLists[name] = scaled.toMutableList().apply {
                        if (replaceIndex != null) set(replaceIndex, newValue) else add(newValue)
                    }
                }
            }
            Type.VFP ->
            {
                targetVfp = vfpFromBase(panel.baseHz, newBaseVTotal, newBaseVfp, targetHz)
            }
        }
        require(targetVfp >= 1) {
            "按当前时钟推算 $targetHz Hz 的 VFP 为 $targetVfp，无法生成"
        }
        // DSI 控制器的帧长寄存器是 16 位，低刷档位按比例加长后不能越界。
        val longest = (panel.rates.indices.maxOf { panel.vTotal(it) } * scale).roundToInt()
        require(longest <= 0xFFFF) { "低刷新率档位的帧长将达到 $longest 行，超出 DSI 寄存器上限，无法提高到该刷新率" }
        perFpsLists.forEach { name ->
            val old = panel.lists[name] ?: return@forEach
            newLists[name] = old.toMutableList().apply {
                if (replaceIndex != null) set(replaceIndex, old[reference]) else add(old[reference])
            }
        }

        if (scale > 1.0)
        {
            warnings += String.format(
                Locale.US,
                "DSI 时钟提高 %.1f%%（%d → %d Hz）。PHY 时序与面板接收端按原频率标定，超出面板能力会黑屏或花屏，请先确认已备份。",
                (scale - 1) * 100, panel.clockHz, newClock
            )
        }
        if (replaceIndex == null)
        {
            warnings += "DFPS 列表加长会让 DTB 变大；镜像带 AVB 时部分机型会直接进入 Fastboot，可改用替换现有档位保持体积。"
        }
        warnings += "能否在系统中选到 $targetHz Hz 还取决于 ROM 的刷新率配置。"

        val changes = buildList {
            add("DFPS 列表: ${panel.rates} → $newRates")
            if (scale > 1.0)
            {
                add("DSI 时钟: ${panel.clockHz} → $newClock Hz")
                add("其余档位 VFP 按比例加大，保持原刷新率；基准时序 VFP ${panel.vFrontPorch} → $newBaseVfp")
            }
            add("新档位 $targetHz Hz 沿用 $referenceHz Hz 档位的 porch，VFP=$targetVfp")
            newLists.keys.filter { it in perFpsLists }.forEach { add("同步扩充 $it") }
        }

        return Calculation(
            targetHz = targetHz,
            replaceIndex = replaceIndex,
            referenceHz = referenceHz,
            clockHz = panel.clockHz,
            newClockHz = newClock,
            newRates = newRates,
            newLists = newLists,
            newBaseVFrontPorch = newBaseVfp,
            targetVFrontPorch = targetVfp,
            changes = changes,
            warnings = warnings
        )
    }

    fun plan(
        candidate: TimingCandidate,
        targetHz: Int,
        replaceHz: Int?,
        sourceText: String = candidate.dtsFile.readText()
    ): TimingDeviceTreePlanner.Plan
    {
        val document = DeviceTreeParser.parse(candidate.entryIndex, sourceText)
        val panel = readPanel(document, candidate)
        val calculation = calculate(panel, targetHz, replaceHz)
        val panelNode = requireNotNull(document.findNode(panel.panelPath))
        val timingNode = requireNotNull(document.findNode(panel.timingPath))

        val operations = buildList<DeviceTreeChange> {
            addCells(candidate.entryIndex, panelNode, DFPS_LIST, calculation.newRates.map(Int::toLong))
            calculation.newLists.forEach { (name, values) -> addCells(candidate.entryIndex, panelNode, name, values) }
            if (calculation.newClockHz != null && calculation.newClockHz != calculation.clockHz)
            {
                addNumber(candidate.entryIndex, timingNode, CLOCK, calculation.newClockHz)
            }
            if (calculation.newBaseVFrontPorch != panel.vFrontPorch)
            {
                addNumber(candidate.entryIndex, timingNode, VFP, calculation.newBaseVFrontPorch.toLong())
            }
        }

        val replayedText = TimingDeviceTreePlanner.replay(sourceText, operations)
        verifyReplay(document, DeviceTreeParser.parse(candidate.entryIndex, replayedText), candidate, calculation)

        return TimingDeviceTreePlanner.Plan(
            operations = operations,
            replayedText = replayedText,
            targetNodePath = candidate.nodePath,
            changes = calculation.changes,
            warnings = calculation.warnings
        )
    }

    private fun verifyReplay(
        before: DeviceTreeDocument,
        after: DeviceTreeDocument,
        candidate: TimingCandidate,
        calculation: Calculation
    )
    {
        require(before.flatten().map { it.path }.toSet() == after.flatten().map { it.path }.toSet()) {
            "DFPS 修改意外改变了设备树节点集合"
        }
        val panel = readPanel(after, candidate)
        require(panel.rates == calculation.newRates) { "DFPS 列表回放校验失败：${panel.rates}" }
        calculation.newLists.forEach { (name, values) ->
            require(panel.lists[name] == values) { "$name 回放校验失败" }
        }
        require(panel.lists.values.all { it.size == panel.rates.size }) { "DFPS 相关列表长度回放后不一致" }
        require(panel.clockHz == calculation.newClockHz) { "DSI 时钟回放校验失败" }
        require(panel.vFrontPorch == calculation.newBaseVFrontPorch) { "基准 VFP 回放校验失败" }
        require(panel.vTotal(panel.rates.indexOf(calculation.targetHz)) > panel.vActive) { "新档位帧长回放校验失败" }
    }

    private fun MutableList<DeviceTreeChange>.addCells(entryIndex: Int, node: DeviceTreeNode, name: String, values: List<Long>)
    {
        val property = requireNotNull(node.properties.firstOrNull { it.name == name }) { "找不到 ${node.path}/$name" }
        if (DtsNumericValueCodec.decodeCells(property.rawValue) == values) return
        add(SetPropertyChange(entryIndex, node.path, name, property.rawValue, DtsNumericValueCodec.encodeCellsLike(property.rawValue, values)))
    }

    private fun MutableList<DeviceTreeChange>.addNumber(entryIndex: Int, node: DeviceTreeNode, name: String, value: Long)
    {
        val property = requireNotNull(node.properties.firstOrNull { it.name == name }) { "找不到 ${node.path}/$name" }
        add(SetPropertyChange(entryIndex, node.path, name, property.rawValue, DtsNumericValueCodec.encodeLike(property.rawValue, value)))
    }

    /** dsi_display_dfps_calc_front_porch：b_fp ± mult_frac(b_total, |old - new|, new)。 */
    internal fun vfpFromBase(baseHz: Int, baseVTotal: Int, baseVfp: Int, hz: Int): Int
    {
        val add = (baseVTotal.toLong() * abs(baseHz - hz) / hz).toInt()
        return if (baseHz > hz) baseVfp + add else baseVfp - add
    }

    private fun raw(node: DeviceTreeNode, name: String): String? = node.properties.firstOrNull { it.name == name }?.rawValue

    private fun cells(node: DeviceTreeNode, name: String): List<Long>? = DtsNumericValueCodec.decodeCells(raw(node, name))

    private fun number(node: DeviceTreeNode, name: String): Long? = DtsNumericValueCodec.decode(raw(node, name))

    private fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }
}
