package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.TimingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun exactPanelWinsOverPanelWhoseNameIsAPrefix() {
        fun candidate(panel: String) = TimingCandidate(
            id = panel, entryIndex = 0, dtsFile = File("dummy.dts"),
            nodePath = "/fragment@0/__overlay__/qcom,mdss_dsi_$panel/qcom,mdss-dsi-display-timings/timing@0",
            nodeStart = 0, nodeEndExclusive = 1, currentHz = 120
        )
        val candidates = listOf(candidate("nt37801_wqhd_plus_cmd"), candidate("nt37801_wqhd_plus_cmd_cphy"))

        assertEquals("nt37801_wqhd_plus_cmd_cphy",
            ActivePanelDetector.findBestMatchCandidate(candidates, "qcom,mdss_dsi_nt37801_wqhd_plus_cmd_cphy")?.id)
        assertEquals("nt37801_wqhd_plus_cmd",
            ActivePanelDetector.findBestMatchCandidate(candidates, "qcom,mdss_dsi_nt37801_wqhd_plus_cmd")?.id)
    }

    @Test
    fun parsesAppliedDtboIndicesFromBootconfigAndCmdline() {
        val bootconfig = """
            androidboot.hardware = "qcom"
            androidboot.dtbo_idx = "3"
            androidboot.dtb_idx = "0"
        """.trimIndent()
        assertEquals("3", ActivePanelDetector.extractDtboIndexValue(bootconfig))
        val cmdline = "console=null androidboot.dtbo_idx=0,5 androidboot.dtb_idx=0 msm_drm.dsi_display0=x"
        assertEquals(setOf(0, 5), ActivePanelDetector.parseDtboIndices(ActivePanelDetector.extractDtboIndexValue(cmdline)!!))
        assertEquals(setOf(3), ActivePanelDetector.parseDtboIndices("3\n"))
        assertEquals(emptySet<Int>(), ActivePanelDetector.parseDtboIndices("3,x"))
        assertEquals(emptySet<Int>(), ActivePanelDetector.parseDtboIndices(""))
        assertNull(ActivePanelDetector.extractDtboIndexValue("androidboot.dtb_idx=0"))
    }

    @Test
    fun appliedDtboEntryDecidesWhichInstanceOfThePanelIsRecommended() {
        fun candidate(entry: Int, hz: Int, panel: String = "panel_AD296_P_3_A0020_dsc_cmd") = TimingCandidate(
            id = "$entry:$panel:$hz", entryIndex = entry, dtsFile = File("dummy.dts"),
            nodePath = "/fragment@0/__overlay__/qcom,mdss_dsi_$panel/qcom,mdss-dsi-display-timings/timing@$hz",
            nodeStart = 0, nodeEndExclusive = 1, currentHz = hz
        )
        val candidates = (0..7).flatMap { listOf(candidate(it, 60), candidate(it, 120)) } + candidate(0, 120, "sim_cmd")
        val detected = "qcom,mdss_dsi_panel_AD296_P_3_A0020_dsc_cmd"

        assertEquals("3:panel_AD296_P_3_A0020_dsc_cmd:120",
            ActivePanelDetector.findBestMatchCandidate(candidates, detected, setOf(3))?.id)
        // Without dtbo_idx the first instance is used, as before.
        assertEquals("0:panel_AD296_P_3_A0020_dsc_cmd:120",
            ActivePanelDetector.findBestMatchCandidate(candidates, detected)?.id)
        // An applied entry lacking the panel must not hide the panel entirely.
        assertEquals("0:sim_cmd:120",
            ActivePanelDetector.findBestMatchCandidate(candidates, "qcom,mdss_dsi_sim_cmd", setOf(3))?.id)
    }
}
