package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimingParameterCalculatorTest {
    // Qualcomm reference panels (e.g. in Meizu 21 DTBO) often omit qcom,mdss-dsi-panel-clockrate.
    private val clockless = TimingCandidate(
        id = "0:0", entryIndex = 0, dtsFile = File("dummy.dts"), nodePath = "/panel/timings/timing@0",
        nodeStart = 0, nodeEndExclusive = 1, currentHz = 120, pixelClockHz = null,
        hActive = 1080, vActive = 2400, hFrontPorch = 48, hBackPorch = 48, hSync = 32,
        vFrontPorch = 20, vBackPorch = 12, vSync = 4
    )

    @Test fun balancedOnClocklessNodeAdjustsPorchesAndLeavesClockToDriver() {
        val result = TimingParameterCalculator.calculate(clockless, 144, PatchStrategy.BALANCED_BLANKING_TIME)
        val withClock = TimingParameterCalculator.calculate(
            clockless.copy(pixelClockHz = 1_000_000_000), 144, PatchStrategy.BALANCED_BLANKING_TIME
        )

        assertNull(result.pixelClockHz)
        assertEquals(PatchStrategy.BALANCED_BLANKING_TIME, result.effectiveStrategy)
        // Same porch solution as a node that declares its clock.
        assertEquals(withClock.vFrontPorch, result.vFrontPorch)
        assertEquals(withClock.vBackPorch, result.vBackPorch)
        assertTrue(result.changes.any { it.contains("自动推导") })
        assertTrue(result.warnings.any { it.contains("panel-clockrate") })
    }

    @Test fun pixelClockOnlyOnClocklessNodeOnlyChangesRefresh() {
        val result = TimingParameterCalculator.calculate(clockless, 144, PatchStrategy.PIXEL_CLOCK_ONLY)
        assertNull(result.pixelClockHz)
        assertNull(result.vFrontPorch)
        assertEquals(144, result.refreshHz)
        assertTrue(result.changes.any { it.contains("×1.200") })
    }
}
