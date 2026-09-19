package io.mo.dtbooverclocker.ui.components

import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimingUtilsTest {

    @Test
    fun testParsePanelIdentifier() {
        val path = "/fragment@81/__overlay__/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_nt37801_wqhd_plus_cmd/qcom,mdss-dsi-display-timings/timing@0"
        val panel = TimingUtils.parsePanelIdentifier(path)
        assertEquals("qcom,mdss_dsi_nt37801_wqhd_plus_cmd", panel)

        val displayName = TimingUtils.formatPanelDisplayName(panel)
        assertTrue("DisplayName should contain NT37801: $displayName", displayName.contains("NT37801"))
        assertTrue("DisplayName should contain WQHD+: $displayName", displayName.contains("WQHD+"))
    }

    @Test
    fun testParseTimingNodeName() {
        val path = "/fragment@81/__overlay__/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_nt37801_wqhd_plus_cmd/qcom,mdss-dsi-display-timings/timing@2"
        val nodeName = TimingUtils.parseTimingNodeName(path)
        assertEquals("timing@2", nodeName)
    }

    @Test
    fun testFormatClock() {
        val clock = 1199900000L
        val formatted = TimingUtils.formatClock(clock)
        assertTrue("Formatted clock should contain 1,199.90 MHz: $formatted", formatted.contains("1,199.90 MHz"))
        assertTrue("Formatted clock should contain 1.20 GHz: $formatted", formatted.contains("1.20 GHz"))
    }

    @Test
    fun testCalculateSimulation() {
        val candidate = TimingCandidate(
            id = "0:100:test",
            entryIndex = 0,
            dtsFile = File("dummy.dts"),
            nodePath = "/fragment@81/__overlay__/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_nt37801_wqhd_plus_cmd/qcom,mdss-dsi-display-timings/timing@0",
            nodeStart = 0,
            nodeEndExclusive = 100,
            currentHz = 120,
            pixelClockHz = 1199900000L,
            hActive = 1440,
            vActive = 3200,
            hFrontPorch = 40,
            hBackPorch = 32,
            hSync = 16,
            vFrontPorch = 12,
            vBackPorch = 8,
            vSync = 4,
            hasOpaquePanelTimings = false
        )

        val sim = TimingUtils.calculateSimulation(candidate, 144, PatchStrategy.BALANCED_BLANKING_TIME)
        assertEquals(120, sim.originalHz)
        assertEquals(144, sim.targetHz)
        assertEquals(24, sim.hzDelta)
        assertEquals(20.0, sim.hzPercentage, 0.01)
        assertEquals(OverclockRisk.MODERATE, sim.risk)
        assertNotNull(sim.estimatedClockHz)
        assertTrue(sim.estimatedClockHz!! > candidate.pixelClockHz!!)
        assertNotNull(sim.estimatedVfp)
        assertNotNull(sim.estimatedVbp)
    }

    @Test
    fun testO1PanelsDetection() {
        val o138 = "qcom,mdss_dsi_o1_38_0c_0b_dsc_cmd"
        val o142 = "qcom,mdss_dsi_o1_42_02_0a_dsc_cmd"
        val dualSim = "qcom,mdss_dsi_dual_sim_cmd"
        val nt37801 = "qcom,mdss_dsi_nt37801_wqhd_plus_cmd"

        assertTrue("o1_38 should be device specific", TimingUtils.isDeviceSpecific(o138))
        assertTrue("o1_42 should be device specific", TimingUtils.isDeviceSpecific(o142))
        assertTrue("dual_sim should be simulation", TimingUtils.isSimulation(dualSim))
        assertTrue("nt37801 should be qcom reference", TimingUtils.isQcomReference(nt37801))

        val name38 = TimingUtils.formatPanelDisplayName(o138)
        val name42 = TimingUtils.formatPanelDisplayName(o142)
        assertTrue("name38 should start with O1-38: $name38", name38.startsWith("O1-38"))
        assertTrue("name42 should start with O1-42: $name42", name42.startsWith("O1-42"))
    }
}

