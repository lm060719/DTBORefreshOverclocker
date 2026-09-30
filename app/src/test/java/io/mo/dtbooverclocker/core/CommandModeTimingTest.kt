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

    private val sharedTiming = """
        qcom,mdss-dsi-panel-width = <1264>;
        qcom,mdss-dsi-panel-height = <2780>;
        qcom,mdss-dsi-panel-clockrate = <1360000000>;
        qcom,mdss-dsi-v-front-porch = <20>;
        qcom,mdss-dsi-v-back-porch = <18>;
        qcom,mdss-mdp-transfer-time-us = <6000>;
    """.trimIndent().replace("\n", "\n                                ")

    private fun panelFixture(
        panelType: String,
        secondSwitchCommand: String,
        firstTiming: String = sharedTiming,
        secondTiming: String = sharedTiming,
        secondHz: Int = 165
    ): File =
        File.createTempFile("switch_panel", ".dts").apply {
            deleteOnExit()
            writeText("""
                /dts-v1/;
                / {
                    qcom,mdss_dsi_mot_boe_ili97680A_678_1264x2780_dsc_cmd_v4 {
                        qcom,mdss-dsi-panel-type = "$panelType";
                        qcom,mdss-dsi-display-timings {
                            timing@0 {
                                qcom,mdss-dsi-panel-framerate = <120>;
                                $firstTiming
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 40 00 00 04 ff 5a a5 00 39 00 00 40 00 00 02 53 20];
                            };
                            timing@1 {
                                qcom,mdss-dsi-panel-framerate = <$secondHz>;
                                $secondTiming
                                qcom,mdss-dsi-timing-switch-command = [$secondSwitchCommand];
                            };
                        };
                    };
                };
            """.trimIndent())
        }

    private val differentSwitch = "39 00 00 40 00 00 04 ff 5a a5 2d 39 00 00 40 00 00 02 cd 01"

    @Test fun flagsCommandModePanelWhoseModesSendDifferentSwitchCommands() {
        val flagged = DtsTimingPatcher.analyzeEntry(0, panelFixture("dsi_cmd_mode", differentSwitch))
        assertEquals(listOf(true, true), flagged.map { it.refreshSetByPanelCommands })

        val identical = DtsTimingPatcher.analyzeEntry(0,
            panelFixture("dsi_cmd_mode", "39 00 00 40 00 00 04 ff 5a a5 00 39 00 00 40 00 00 02 53 20"))
        assertTrue(identical.none { it.refreshSetByPanelCommands })

        val video = DtsTimingPatcher.analyzeEntry(0, panelFixture("dsi_video_mode", differentSwitch))
        assertTrue(video.none { it.refreshSetByPanelCommands })
    }

    @Test fun doesNotFlagSameRateModesThatOnlySwitchResolution() {
        val fhd = sharedTiming.replace("<1264>", "<948>").replace("<2780>", "<2085>")
        val candidates = DtsTimingPatcher.analyzeEntry(0,
            panelFixture("dsi_cmd_mode", differentSwitch, secondTiming = fhd, secondHz = 120))
        assertEquals(2, candidates.size)
        assertTrue(candidates.none { it.refreshSetByPanelCommands })
    }

    @Test fun doesNotFlagWhenModesCarryNoOwnTimingValues() {
        val bare = DtsTimingPatcher.analyzeEntry(0,
            panelFixture("dsi_cmd_mode", differentSwitch, firstTiming = "", secondTiming = ""))
        assertTrue(bare.none { it.refreshSetByPanelCommands })

        // A clock inherited from the panel node is shared by every mode and must not count as "identical timing".
        val inherited = File.createTempFile("inherited_clock", ".dts").apply {
            deleteOnExit()
            writeText("""
                /dts-v1/;
                / {
                    panel {
                        qcom,mdss-dsi-panel-type = "dsi_cmd_mode";
                        qcom,mdss-dsi-panel-clockrate = <1360000000>;
                        qcom,mdss-dsi-display-timings {
                            timing@0 {
                                qcom,mdss-dsi-panel-framerate = <60>;
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 00 00 00 02 2f 02];
                            };
                            timing@1 {
                                qcom,mdss-dsi-panel-framerate = <120>;
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 00 00 00 02 2f 00];
                            };
                        };
                    };
                };
            """.trimIndent())
        }
        val candidates = DtsTimingPatcher.analyzeEntry(0, inherited)
        assertEquals(listOf(1_360_000_000L, 1_360_000_000L), candidates.map { it.pixelClockHz })
        assertTrue(candidates.none { it.refreshSetByPanelCommands })
    }

    @Test fun videoPanelWithoutTimingsWrapperIgnoresSiblingCommandPanel() {
        val file = File.createTempFile("flat_panels", ".dts").apply {
            deleteOnExit()
            writeText("""
                /dts-v1/;
                / {
                    dsi_panels {
                        cmd_panel {
                            qcom,mdss-dsi-panel-type = "dsi_cmd_mode";
                        };
                        video_panel {
                            qcom,mdss-dsi-panel-type = "dsi_video_mode";
                            timing@0 {
                                qcom,mdss-dsi-panel-framerate = <60>;
                                $sharedTiming
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 00 00 00 02 2f 02];
                            };
                            timing@1 {
                                qcom,mdss-dsi-panel-framerate = <120>;
                                $sharedTiming
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 00 00 00 02 2f 00];
                            };
                        };
                    };
                };
            """.trimIndent())
        }
        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        assertTrue(candidates.none { it.refreshSetByPanelCommands })
    }

    @Test fun switchCommandsDifferingOnlyInWhitespaceOrCaseAreTheSame() {
        val reformatted = "39 00 00 40  00 00 04 FF 5A A5 00\n39 00 00 40 00 00 02 53 20"
        val candidates = DtsTimingPatcher.analyzeEntry(0, panelFixture("dsi_cmd_mode", reformatted))
        assertEquals(2, candidates.size)
        assertTrue(candidates.none { it.refreshSetByPanelCommands })
    }

    @Test fun doesNotFlagCommandModePanelWhoseModesAlsoDifferInTiming() {
        val file = File.createTempFile("xiaomi_panel", ".dts").apply {
            deleteOnExit()
            writeText("""
                /dts-v1/;
                / {
                    qcom,mdss_dsi_m16t_36_02_0a_dsc_cmd {
                        qcom,mdss-dsi-panel-type = "dsi_cmd_mode";
                        qcom,mdss-dsi-display-timings {
                            timing@wqhd_normal_60hz_index_00 {
                                qcom,mdss-dsi-panel-framerate = <60>;
                                qcom,mdss-dsi-panel-clockrate = <680000000>;
                                qcom,mdss-mdp-transfer-time-us = <14600>;
                                qcom,mdss-dsi-v-front-porch = <16>;
                                qcom,mdss-dsi-v-back-porch = <24>;
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 00 00 00 02 2f 02];
                            };
                            timing@wqhd_normal_120hz_index_01 {
                                qcom,mdss-dsi-panel-framerate = <120>;
                                qcom,mdss-dsi-panel-clockrate = <1360000000>;
                                qcom,mdss-mdp-transfer-time-us = <7300>;
                                qcom,mdss-dsi-v-front-porch = <16>;
                                qcom,mdss-dsi-v-back-porch = <24>;
                                qcom,mdss-dsi-timing-switch-command = [39 00 00 00 00 00 02 2f 00];
                            };
                        };
                    };
                };
            """.trimIndent())
        }
        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        assertTrue(candidates.none { it.refreshSetByPanelCommands })
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
