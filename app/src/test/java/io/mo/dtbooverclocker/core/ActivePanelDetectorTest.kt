package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.TimingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActivePanelDetectorTest {

    @Test
    fun testNormalizeIdentifier() {
        assertEquals("o1_42_02_0a_dsc_cmd", ActivePanelDetector.normalizeIdentifier("qcom,mdss_dsi_o1_42_02_0a_dsc_cmd"))
        assertEquals("o1_38_0c_0b_dsc_cmd", ActivePanelDetector.normalizeIdentifier("mdss_dsi_o1_38_0c_0b_dsc_cmd"))
        assertEquals("nt37801_wqhd_plus_cmd", ActivePanelDetector.normalizeIdentifier("dsi_nt37801_wqhd_plus_cmd"))
    }

    @Test
    fun testMatchPanel() {
        val o1_42 = "qcom,mdss_dsi_o1_42_02_0a_dsc_cmd"
        val o1_38 = "qcom,mdss_dsi_o1_38_0c_0b_dsc_cmd"

        // Exact match with cmdline output
        assertTrue(ActivePanelDetector.matchPanel(o1_42, "qcom,mdss_dsi_o1_42_02_0a_dsc_cmd"))
        // Match with mi_display output (missing qcom, prefix)
        assertTrue(ActivePanelDetector.matchPanel(o1_42, "mdss_dsi_o1_42_02_0a_dsc_cmd"))
        // Substring / token match
        assertTrue(ActivePanelDetector.matchPanel(o1_42, "o1_42"))

        // Should not match other panel
        assertFalse(ActivePanelDetector.matchPanel(o1_38, "qcom,mdss_dsi_o1_42_02_0a_dsc_cmd"))
        assertFalse(ActivePanelDetector.matchPanel(o1_38, "o1_42"))
    }

    @Test
    fun testFindBestMatchCandidate() {
        val dummyFile = File.createTempFile("dummy", ".dts")
        val candidate38 = TimingCandidate(
            id = "0:0:o1_38_120",
            entryIndex = 0,
            dtsFile = dummyFile,
            nodePath = "/soc/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_o1_38_0c_0b_dsc_cmd/display-timings/timing@0",
            nodeStart = 0,
            nodeEndExclusive = 100,
            currentHz = 120
        )
        val candidate42_60 = TimingCandidate(
            id = "0:100:o1_42_60",
            entryIndex = 0,
            dtsFile = dummyFile,
            nodePath = "/soc/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_o1_42_02_0a_dsc_cmd/display-timings/timing@0",
            nodeStart = 100,
            nodeEndExclusive = 200,
            currentHz = 60
        )
        val candidate42_120 = TimingCandidate(
            id = "0:200:o1_42_120",
            entryIndex = 0,
            dtsFile = dummyFile,
            nodePath = "/soc/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_o1_42_02_0a_dsc_cmd/display-timings/timing@1",
            nodeStart = 200,
            nodeEndExclusive = 300,
            currentHz = 120
        )
        val candidate42_144 = TimingCandidate(
            id = "0:300:o1_42_144",
            entryIndex = 0,
            dtsFile = dummyFile,
            nodePath = "/soc/qcom,mdss_mdp@ae00000/qcom,mdss_dsi_o1_42_02_0a_dsc_cmd/display-timings/timing@2",
            nodeStart = 300,
            nodeEndExclusive = 400,
            currentHz = 144
        )

        val candidates = listOf(candidate38, candidate42_60, candidate42_120, candidate42_144)

        val best = ActivePanelDetector.findBestMatchCandidate(candidates, "mdss_dsi_o1_42_02_0a_dsc_cmd")
        assertNotNull(best)
        assertEquals("0:200:o1_42_120", best?.id)

        val dynamicFirst = candidate42_120.copy(id = "dynamic", hasVendorDynamicMode = true)
        assertEquals(candidate42_120.id, ActivePanelDetector.findBestMatchCandidate(
            listOf(dynamicFirst) + candidates, "mdss_dsi_o1_42_02_0a_dsc_cmd"
        )?.id)
    }
}
