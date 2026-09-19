package io.mo.dtbooverclocker.ui.components

import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 屏幕面板分组键，用于将分散在各个 DTB / 片段中的时序候选归类到具体的屏幕面板。
 */
data class PanelGroupKey(
    val entryIndex: Int,
    val panelIdentifier: String,
    val panelDisplayName: String,
    val isDeviceSpecific: Boolean = false,
    val isSimulation: Boolean = false
) {
    val title: String
        get() = "$panelDisplayName · DTB[$entryIndex]"
}

/**
 * 超频风险评级
 */
enum class OverclockRisk(
    val label: String,
    val description: String
) {
    SAFE("稳妥区间", "超频增幅 ≤ 15%，通常各厂商面板均有充足裕量"),
    MODERATE("进阶区间", "超频增幅 15% ~ 35%，需密切观察是否出现水波纹、偏色或发热"),
    EXTREME("极限区间", "超频增幅 > 35%，极易触发 MIPI DSI 链路失锁或黑屏，建议先备好救砖")
}

/**
 * 超频模拟推演结果
 */
data class TimingSimulation(
    val originalHz: Int,
    val targetHz: Int,
    val hzDelta: Int,
    val hzPercentage: Double,
    val originalClockHz: Long?,
    val estimatedClockHz: Long?,
    val clockMultiplier: Double,
    val originalVfp: Int?,
    val estimatedVfp: Int?,
    val originalVbp: Int?,
    val estimatedVbp: Int?,
    val originalVTotal: Long?,
    val estimatedVTotal: Long?,
    val risk: OverclockRisk,
    val calculationNote: String
)

object TimingUtils {

    /**
     * 从设备树路径解析屏幕面板节点名称。
     * 例如从 "/fragment@81/__overlay__/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_nt37801_wqhd_plus_cmd/qcom,mdss-dsi-display-timings/timing@0"
     * 提取出 "qcom,mdss_dsi_nt37801_wqhd_plus_cmd"
     */
    fun parsePanelIdentifier(nodePath: String): String {
        val segments = nodePath.trim().split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return "unknown_panel"

        val timingsIndex = segments.indexOfLast { it.contains("display-timings", ignoreCase = true) }
        if (timingsIndex > 0) {
            return segments[timingsIndex - 1]
        }

        val lastIndex = segments.lastIndex
        if (lastIndex >= 1 && segments[lastIndex].startsWith("timing", ignoreCase = true)) {
            return segments[lastIndex - 1]
        }

        return segments.firstOrNull { seg ->
            seg.contains("dsi", ignoreCase = true) ||
                seg.contains("panel", ignoreCase = true) ||
                seg.contains("nt3", ignoreCase = true) ||
                seg.contains("sw4", ignoreCase = true)
        } ?: segments.last()
    }

    /**
     * 将原始设备树面板标识转化为人类更易阅读的友好显示名称。
     * 例如 "qcom,mdss_dsi_o1_38_0c_0b_dsc_cmd" -> "O1-38 (0c_0b DSC CMD)"
     * "qcom,mdss_dsi_nt37801_wqhd_plus_cmd" -> "NT37801 (WQHD+ CMD)"
     */
    fun formatPanelDisplayName(panelIdentifier: String): String {
        var clean = panelIdentifier
            .removePrefix("qcom,")
            .removePrefix("mdss_dsi_")
            .removePrefix("mdss-dsi-")
            .removePrefix("mdss_")
            .removePrefix("dsi_")

        clean = clean.replace("_", " ").trim()
        val parts = clean.split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return panelIdentifier

        // 识别项目代号 + 屏幕代号模式（如 o1 38 / o1 42 -> O1-38 / O1-42）
        val (mainPart, remainingParts) = if (parts.size >= 2 && parts[0].length <= 4 && parts[1].all { it.isDigit() }) {
            "${parts[0].uppercase(Locale.ROOT)}-${parts[1]}" to parts.drop(2)
        } else {
            parts.first().uppercase(Locale.ROOT) to parts.drop(1)
        }

        val tags = remainingParts.map { tag ->
            when (tag.lowercase(Locale.ROOT)) {
                "wqhd" -> "WQHD"
                "plus" -> "+"
                "fhd" -> "FHD"
                "cmd" -> "CMD"
                "vid", "video" -> "Video"
                "dsc" -> "DSC"
                else -> tag.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        }

        return if (tags.isNotEmpty()) {
            val tagString = tags.joinToString(" ")
                .replace("+ CMD", "+ CMD")
                .replace("WQHD +", "WQHD+")
                .replace("FHD +", "FHD+")
            "$mainPart ($tagString)"
        } else {
            mainPart
        }
    }

    /**
     * 判断是否为高通虚拟仿真或测试屏节点（如 sim_cmd, dual_sim 等）
     */
    fun isSimulation(identifier: String): Boolean {
        val lower = identifier.lowercase(Locale.ROOT)
        return lower.contains("sim_") ||
            lower.contains("_sim") ||
            lower.contains("dual_sim") ||
            lower.contains("ext_bridge") ||
            lower.contains("test_")
    }

    /**
     * 判断是否为高通 BSP 公版样例面板驱动（如 nt37801, sharp, vtdr6130 等）
     */
    fun isQcomReference(identifier: String): Boolean {
        val clean = identifier.lowercase(Locale.ROOT)
            .removePrefix("qcom,mdss_dsi_")
            .removePrefix("qcom,")
            .removePrefix("mdss_dsi_")
        return clean.startsWith("nt37801") ||
            clean.startsWith("sharp") ||
            clean.startsWith("vtdr6130")
    }

    /**
     * 判断是否为手机机型专属定制面板（非仿真测试、非高通公版样例，如 o1_38 / o1_42）
     */
    fun isDeviceSpecific(identifier: String): Boolean {
        return !isSimulation(identifier) && !isQcomReference(identifier)
    }

    /**
     * 提取时序节点名称（如 "timing@0" 或 "timing@1"）
     */
    fun parseTimingNodeName(nodePath: String): String {
        return nodePath.trim().split('/').lastOrNull { it.isNotBlank() } ?: "timing"
    }

    /**
     * 格式化时钟频率（如 1199900000 -> 1,199.90 MHz）
     */
    fun formatClock(clockHz: Long?): String {
        if (clockHz == null || clockHz <= 0) return "未指定 / 动态"
        val mhz = clockHz / 1_000_000.0
        return if (mhz >= 1000.0) {
            String.format(Locale.US, "%,.2f MHz (%.2f GHz)", mhz, mhz / 1000.0)
        } else {
            String.format(Locale.US, "%,.2f MHz", mhz)
        }
    }

    /**
     * 紧凑型时钟格式（用于卡片徽标，如 "1,199.9 MHz"）
     */
    fun formatClockCompact(clockHz: Long?): String {
        if (clockHz == null || clockHz <= 0) return "未定义时钟"
        val mhz = clockHz / 1_000_000.0
        return String.format(Locale.US, "%,.1f MHz", mhz)
    }

    /**
     * 将候选列表按面板与 DTB 条目归类，并优先按「机型专属 > 公版样例 > 仿真测试」排序
     */
    fun groupCandidates(candidates: List<TimingCandidate>): Map<PanelGroupKey, List<TimingCandidate>> {
        val rawGroups = candidates.groupBy { candidate ->
            val identifier = parsePanelIdentifier(candidate.nodePath)
            val displayName = formatPanelDisplayName(identifier)
            val isSim = isSimulation(identifier)
            val isDev = isDeviceSpecific(identifier)
            PanelGroupKey(
                entryIndex = candidate.entryIndex,
                panelIdentifier = identifier,
                panelDisplayName = displayName,
                isDeviceSpecific = isDev,
                isSimulation = isSim
            )
        }

        return rawGroups.toList()
            .sortedWith(
                compareByDescending<Pair<PanelGroupKey, List<TimingCandidate>>> { it.first.isDeviceSpecific }
                    .thenBy { it.first.isSimulation }
                    .thenBy { it.first.panelDisplayName }
            )
            .toMap()
    }

    /**
     * 实时预估超频参数
     */
    fun calculateSimulation(
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        customParams: CustomTimingParams? = null
    ): TimingSimulation {
        val originalHz = candidate.currentHz
        val hzDelta = targetHz - originalHz
        val hzPercentage = if (originalHz > 0) (hzDelta.toDouble() / originalHz) * 100.0 else 0.0

        val risk = when {
            hzPercentage <= 15.0 -> OverclockRisk.SAFE
            hzPercentage <= 35.0 -> OverclockRisk.MODERATE
            else -> OverclockRisk.EXTREME
        }

        val originalClock = candidate.pixelClockHz
        val vActive = candidate.vActive?.toLong()
        val vfp = candidate.vFrontPorch?.toLong()
        val vbp = candidate.vBackPorch?.toLong()
        val vsync = candidate.vSync?.toLong()

        var estimatedClock: Long? = null
        var clockMultiplier = 1.0
        var newVfp: Int? = null
        var newVbp: Int? = null
        var originalVTotal: Long? = null
        var estimatedVTotal: Long? = null
        var note = ""

        if (originalHz > 0) {
            val ratio = targetHz.toDouble() / originalHz

            when (strategy) {
                PatchStrategy.FRAMERATE_ONLY -> {
                    estimatedClock = originalClock
                    clockMultiplier = 1.0
                    note = "仅修改 framerate 属性；不缩放 Pixel Clock 与垂直消隐行数"
                }

                PatchStrategy.PIXEL_CLOCK_ONLY -> {
                    if (originalClock != null) {
                        estimatedClock = (originalClock * ratio).roundToLong()
                        clockMultiplier = ratio
                    }
                    note = "Pixel Clock 等比缩放 ×${String.format(Locale.US, "%.3f", ratio)}；垂直消隐行数不变"
                }

                PatchStrategy.BALANCED_BLANKING_TIME -> {
                    if (vActive != null && vfp != null && vbp != null && vsync != null && originalClock != null) {
                        val fixedVertical = vActive + vsync
                        val porchVertical = vfp + vbp
                        val oldVt = fixedVertical + porchVertical
                        originalVTotal = oldVt
                        val denominator = oldVt - ratio * porchVertical

                        if (denominator > 0.0) {
                            val k = ratio * fixedVertical / denominator
                            clockMultiplier = k
                            val newPorchTotal = max(2, (porchVertical * k).roundToInt())
                            var calcVfp = max(1, (newPorchTotal.toDouble() * vfp / porchVertical).roundToInt())
                            var calcVbp = newPorchTotal - calcVfp
                            if (calcVbp < 1) {
                                calcVbp = 1
                                calcVfp = newPorchTotal - 1
                            }
                            newVfp = calcVfp
                            newVbp = calcVbp
                            val newVt = fixedVertical + calcVfp + calcVbp
                            estimatedVTotal = newVt
                            estimatedClock = (originalClock.toDouble() * ratio * newVt.toDouble() / oldVt.toDouble()).roundToLong()
                            note = "平衡消隐：时钟倍率 ×${String.format(Locale.US, "%.3f", k)}，VFP ${candidate.vFrontPorch}→$newVfp, VBP ${candidate.vBackPorch}→$newVbp"
                        } else {
                            // 分母 <= 0 说明超频过高导致无法在正向消隐下求解，回退简单比例
                            estimatedClock = (originalClock * ratio).roundToLong()
                            clockMultiplier = ratio
                            note = "超频幅度过大，超出消隐时间平衡解范围，已自动降级为等比时钟预估"
                        }
                    } else if (originalClock != null) {
                        estimatedClock = (originalClock * ratio).roundToLong()
                        clockMultiplier = ratio
                        note = "该节点缺少完整消隐参数，回退为 Pixel Clock 等比预估"
                    } else {
                        note = "无可用 Pixel Clock 属性"
                    }
                }

                PatchStrategy.CUSTOM -> {
                    estimatedClock = customParams?.pixelClockHz ?: originalClock
                    clockMultiplier = if (originalClock != null && originalClock > 0 && estimatedClock != null) {
                        estimatedClock.toDouble() / originalClock
                    } else 1.0
                    newVfp = customParams?.vFrontPorch ?: candidate.vFrontPorch
                    newVbp = customParams?.vBackPorch ?: candidate.vBackPorch

                    if (vActive != null && vsync != null && newVfp != null && newVbp != null) {
                        originalVTotal = if (vfp != null && vbp != null) vActive + vsync + vfp + vbp else null
                        val newVt = vActive + vsync + newVfp + newVbp
                        estimatedVTotal = newVt

                        // 尝试计算理论物理刷新率: clock / (HTotal * VTotal)
                        val hFp = (customParams?.hFrontPorch ?: candidate.hFrontPorch)?.toLong()
                        val hBp = (customParams?.hBackPorch ?: candidate.hBackPorch)?.toLong()
                        val hAct = candidate.hActive?.toLong()
                        val hSync = candidate.hSync?.toLong()

                        if (estimatedClock != null && hAct != null && hFp != null && hSync != null && hBp != null) {
                            val hTotal = hAct + hFp + hSync + hBp
                            if (hTotal > 0 && newVt > 0) {
                                val theoreticalHz = estimatedClock.toDouble() / (hTotal.toDouble() * newVt.toDouble())
                                note = "自定义参数：理论物理刷新率 ≈ ${String.format(Locale.US, "%.2f", theoreticalHz)} Hz (VFP=$newVfp, VBP=$newVbp)"
                            } else {
                                note = "自定义参数：时钟 ${formatClockCompact(estimatedClock)}，VFP=$newVfp, VBP=$newVbp"
                            }
                        } else {
                            note = "自定义参数：时钟 ${formatClockCompact(estimatedClock)}，VFP=$newVfp, VBP=$newVbp"
                        }
                    } else {
                        note = "自定义参数：时钟 ${formatClockCompact(estimatedClock)}"
                    }
                }
            }
        }

        return TimingSimulation(
            originalHz = originalHz,
            targetHz = targetHz,
            hzDelta = hzDelta,
            hzPercentage = hzPercentage,
            originalClockHz = originalClock,
            estimatedClockHz = estimatedClock,
            clockMultiplier = clockMultiplier,
            originalVfp = candidate.vFrontPorch,
            estimatedVfp = newVfp,
            originalVbp = candidate.vBackPorch,
            estimatedVbp = newVbp,
            originalVTotal = originalVTotal,
            estimatedVTotal = estimatedVTotal,
            risk = risk,
            calculationNote = note
        )
    }
}

