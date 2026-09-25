package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 纯刷新率参数计算器。
 *
 * 不读写 DTS，也不依赖文本替换。输入 TimingCandidate 与目标策略，输出需要写入
 * 设备树的目标参数及风险提示。这样刷新率模块可以和 DeviceTreeChange 完全解耦，
 * 旧 DtsTimingPatcher 仅保留分析与回归验证用途。
 */
object TimingParameterCalculator
{
    data class Result(
        val refreshHz: Int,
        val pixelClockHz: Long? = null,
        val vFrontPorch: Int? = null,
        val vBackPorch: Int? = null,
        val hFrontPorch: Int? = null,
        val hBackPorch: Int? = null,
        val mdpTransferTimeUs: Long? = null,
        val effectiveStrategy: PatchStrategy,
        val changes: List<String>,
        val warnings: List<String>
    )

    fun calculate(
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        customParams: CustomTimingParams? = null
    ): Result
    {
        require(targetHz in 30..360) {
            "目标刷新率必须在 30..360 Hz 范围内"
        }
        require(targetHz != candidate.currentHz) {
            "目标刷新率与当前刷新率相同"
        }
        require(!candidate.hasVendorDynamicMode) {
            "该档位含厂商自动变频/idle 配置，不能直接改成普通高刷档位。请在同一面板下选择 normal 普通档位作为模板。"
        }

        val changes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        changes += "刷新率: ${candidate.currentHz} -> $targetHz Hz"

        val effectiveStrategy = if (
            strategy == PatchStrategy.BALANCED_BLANKING_TIME &&
            candidate.mdpTransferTimeUs != null
        )
        {
            warnings += "检测到 MDP 传输预算：保持原前后肩，按刷新率缩放时钟及传输时间。"
            PatchStrategy.PIXEL_CLOCK_ONLY
        }
        else
        {
            strategy
        }

        // Qualcomm DSI makes qcom,mdss-dsi-panel-clockrate optional: without it the driver derives
        // the link rate from H_total × V_total × refresh × bpp, so the new timing alone scales the clock.
        val clockDerivedByDriver = candidate.pixelClockHz == null
        if (clockDerivedByDriver && effectiveStrategy != PatchStrategy.FRAMERATE_ONLY && effectiveStrategy != PatchStrategy.CUSTOM)
        {
            warnings += "该档位及其父节点未定义 panel-clockrate：DSI 驱动会按 H_total×V_total×刷新率×bpp 自动推导链路时钟，因此不写入时钟属性。"
        }

        var pixelClockHz: Long? = null
        var vFrontPorch: Int? = null
        var vBackPorch: Int? = null
        var hFrontPorch: Int? = null
        var hBackPorch: Int? = null

        when (effectiveStrategy)
        {
            PatchStrategy.FRAMERATE_ONLY ->
            {
                warnings += "仅修改 framerate，不会自动保证 DSI 链路时钟与 porch 满足目标刷新率。"
            }

            PatchStrategy.PIXEL_CLOCK_ONLY ->
            {
                val ratio = targetHz.toDouble() / candidate.currentHz.toDouble()
                val oldClock = candidate.pixelClockHz
                if (oldClock == null)
                {
                    changes += "链路时钟: 由驱动按新刷新率自动推导 (×${String.format(Locale.US, "%.3f", ratio)})"
                }
                else
                {
                    val newClock = (oldClock.toDouble() * ratio).roundToLong()
                    validatePixelClock(newClock)
                    pixelClockHz = newClock
                    changes += "Pixel Clock: $oldClock -> $newClock Hz"
                }
            }

            PatchStrategy.BALANCED_BLANKING_TIME ->
            {
                val oldClock = candidate.pixelClockHz
                val vActive = requireValue(candidate.vActive, "vActive")
                val vfp = requireValue(candidate.vFrontPorch, "vFrontPorch")
                val vbp = requireValue(candidate.vBackPorch, "vBackPorch")
                val vsync = requireValue(candidate.vSync, "vSync")

                val fixedVertical = vActive.toLong() + vsync.toLong()
                val porchVertical = vfp.toLong() + vbp.toLong()
                val oldVTotal = fixedVertical + porchVertical
                require(fixedVertical > 0 && porchVertical >= 2) {
                    "DTS 垂直时序参数不合法"
                }

                val ratio = targetHz.toDouble() / candidate.currentHz.toDouble()
                val denominator = oldVTotal.toDouble() - ratio * porchVertical.toDouble()
                require(denominator > 0.0) {
                    "目标刷新率过高，无法在保持垂直 blanking 时间尺度的条件下求解"
                }

                val multiplier = ratio * fixedVertical.toDouble() / denominator
                require(multiplier in 0.50..3.00) {
                    "计算得到的时钟倍率 $multiplier 超出安全计算边界 0.50..3.00"
                }

                val newPorchTotal = max(2, (porchVertical * multiplier).roundToInt())
                var newVfp = max(
                    1,
                    (newPorchTotal.toDouble() * vfp.toDouble() / porchVertical.toDouble()).roundToInt()
                )
                var newVbp = newPorchTotal - newVfp
                if (newVbp < 1)
                {
                    newVbp = 1
                    newVfp = newPorchTotal - 1
                }

                val newVTotal = fixedVertical + newVfp + newVbp
                val clockRatio = ratio * newVTotal.toDouble() / oldVTotal.toDouble()
                if (oldClock == null)
                {
                    changes += "链路时钟: 由驱动按新时序自动推导 (×${String.format(Locale.US, "%.3f", clockRatio)})"
                }
                else
                {
                    val newClock = (oldClock.toDouble() * clockRatio).roundToLong()
                    validatePixelClock(newClock)
                    pixelClockHz = newClock
                    changes += "Pixel Clock: $oldClock -> $newClock Hz"
                }

                vFrontPorch = newVfp
                vBackPorch = newVbp
                changes += "VFP: $vfp -> $newVfp lines"
                changes += "VBP: $vbp -> $newVbp lines"
                warnings += "水平时序保持不变；垂直 sync 宽度保持不变。"

                if (candidate.hasOpaquePanelTimings)
                {
                    warnings += "检测到 qcom,mdss-dsi-panel-timings PHY 字节数组；该硬件相关数组不会做通用等比修改。"
                }
            }

            PatchStrategy.CUSTOM ->
            {
                val oldClock = candidate.pixelClockHz
                val newClock = customParams?.pixelClockHz ?: oldClock
                if (newClock != null)
                {
                    validatePixelClock(newClock)
                    pixelClockHz = newClock
                    changes += "Pixel Clock: ${oldClock ?: "未定义"} -> $newClock Hz"
                }

                customParams?.vFrontPorch?.let {
                    require(it >= 0) { "VFP 不能为负数" }
                    vFrontPorch = it
                    changes += "VFP: ${candidate.vFrontPorch ?: "未定义"} -> $it lines"
                }
                customParams?.vBackPorch?.let {
                    require(it >= 0) { "VBP 不能为负数" }
                    vBackPorch = it
                    changes += "VBP: ${candidate.vBackPorch ?: "未定义"} -> $it lines"
                }
                customParams?.hFrontPorch?.let {
                    require(it >= 0) { "HFP 不能为负数" }
                    hFrontPorch = it
                    changes += "HFP: ${candidate.hFrontPorch ?: "未定义"} -> $it px"
                }
                customParams?.hBackPorch?.let {
                    require(it >= 0) { "HBP 不能为负数" }
                    hBackPorch = it
                    changes += "HBP: ${candidate.hBackPorch ?: "未定义"} -> $it px"
                }

                warnings += "已应用自定义时序参数。请确保 Pixel Clock 与消隐参数相互匹配，以避免屏幕失步或黑屏。"
                if (candidate.hasOpaquePanelTimings)
                {
                    warnings += "检测到 qcom,mdss-dsi-panel-timings PHY 字节数组；该硬件相关数组不会做通用等比修改。"
                }
            }
        }

        val transfer = candidate.mdpTransferTimeUs?.let { originalTransfer ->
            val newTransfer = if (strategy == PatchStrategy.FRAMERATE_ONLY)
            {
                originalTransfer
            }
            else
            {
                (
                    originalTransfer.toDouble() *
                        candidate.currentHz.toDouble() /
                        targetHz.toDouble()
                    ).roundToLong()
            }

            require(newTransfer > 0 && newTransfer < 1_000_000.0 / targetHz) {
                "MDP 传输时间 $newTransfer µs 必须小于 $targetHz Hz 的帧周期；不能只修改 Framerate"
            }

            if (newTransfer != originalTransfer)
            {
                changes += "MDP Transfer: $originalTransfer -> $newTransfer µs"
            }
            newTransfer
        }

        return Result(
            refreshHz = targetHz,
            pixelClockHz = pixelClockHz,
            vFrontPorch = vFrontPorch,
            vBackPorch = vBackPorch,
            hFrontPorch = hFrontPorch,
            hBackPorch = hBackPorch,
            mdpTransferTimeUs = transfer,
            effectiveStrategy = effectiveStrategy,
            changes = changes,
            warnings = warnings
        )
    }

    private fun validatePixelClock(clock: Long)
    {
        require(clock in 1_000_000L..4_000_000_000L) {
            "计算得到的 Pixel Clock=$clock Hz 超出 1 MHz..4 GHz 的防呆范围"
        }
    }

    private fun requireValue(value: Int?, name: String): Int
    {
        return value ?: error("缺少 $name，无法执行平衡时序策略")
    }
}
