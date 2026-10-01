package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.ui.components.TimingUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommandModeTimingTest {
    private fun fixture(extra: String = ""): File = File.createTempFile("command_panel", ".dts").apply {
        deleteOnExit()
        writeText("""
            /dts-v1/;
            / {
                panel {
                    timing@wqhd_normal_120hz_index_01 {
                        qcom,mdss-dsi-panel-framerate = <120>;
                        qcom,mdss-dsi-panel-clockrate = <1360000000>;
                        qcom,mdss-mdp-transfer-time-us = <7300>;
                        qcom,mdss-dsi-v-front-porch = <16>;
                        qcom,mdss-dsi-v-back-porch = <24>;
                        $extra
                    };
                    timing@wqhd_auto_120_to_30hz_index_10 {
                        qcom,mdss-dsi-panel-framerate = <120>;
                        mi,mdss-dsi-ddic-mode = "auto";
                        mi,mdss-dsi-sf-framerate = <120>;
                    };
                };
            };
        """.trimIndent())
    }

    @Test fun balancedCommandModeMatchesKnownWorkingClockPorchesAndTransferTime() {
        val file = fixture()
        val original = DtsTimingPatcher.analyzeEntry(0, file).first()
        assertEquals(7300L, original.mdpTransferTimeUs)
        val result = DtsTimingPatcher.patch(original, 144, PatchStrategy.BALANCED_BLANKING_TIME, PatchMode.APPEND_NEW)
        file.writeText(result.text)
        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        val added = candidates.last()
        assertTrue(added.nodePath.endsWith("timing@wqhd_normal_144hz_index_11"))
        assertEquals(144, added.currentHz)
        assertEquals(1_632_000_000L, added.pixelClockHz)
        assertEquals(16, added.vFrontPorch)
        assertEquals(24, added.vBackPorch)
        assertEquals(6083L, added.mdpTransferTimeUs)
        assertEquals(listOf(120, 120, 144), candidates.map { it.currentHz })
        assertEquals(7300L, candidates.first().mdpTransferTimeUs)
        val preview = TimingUtils.calculateSimulation(original, 144, PatchStrategy.BALANCED_BLANKING_TIME)
        assertEquals(added.pixelClockHz, preview.estimatedClockHz)
        assertTrue(preview.calculationNote.contains("6083"))
    }

    @Test fun refusesDynamicTemplateForBothAppendAndOverwrite() {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).last()
        assertTrue(candidate.hasVendorDynamicMode)
        for (mode in listOf(PatchMode.APPEND_NEW, PatchMode.OVERWRITE_EXISTING)) {
            assertThrows(IllegalArgumentException::class.java) {
                DtsTimingPatcher.patch(candidate, 144, PatchStrategy.PIXEL_CLOCK_ONLY, mode)
            }
        }
        // Deleting a bad dynamic mode is still supported.
        DtsTimingPatcher.patch(candidate, 120, PatchStrategy.PIXEL_CLOCK_ONLY, PatchMode.DELETE_EXISTING)
    }

    @Test fun refusesFramerateOnlyWhenTransferExceedsFrameBudget() {
        val candidate = DtsTimingPatcher.analyzeEntry(0, fixture()).first()
        assertThrows(IllegalArgumentException::class.java) {
            DtsTimingPatcher.patch(candidate, 144, PatchStrategy.FRAMERATE_ONLY)
        }
    }

    @Test fun clockStrategyAlsoScalesTransferAndCloningCannotDuplicatePhandles() {
        val file = fixture()
        val result = DtsTimingPatcher.patch(DtsTimingPatcher.analyzeEntry(0, file).first(), 144, PatchStrategy.PIXEL_CLOCK_ONLY)
        file.writeText(result.text)
        assertEquals(6083L, DtsTimingPatcher.analyzeEntry(0, file).first().mdpTransferTimeUs)
        val referenced = DtsTimingPatcher.analyzeEntry(0, fixture("phandle = <42>;")).first()
        assertThrows(IllegalArgumentException::class.java) {
            DtsTimingPatcher.patch(referenced, 144, PatchStrategy.PIXEL_CLOCK_ONLY, PatchMode.APPEND_NEW)
        }
    }
}
