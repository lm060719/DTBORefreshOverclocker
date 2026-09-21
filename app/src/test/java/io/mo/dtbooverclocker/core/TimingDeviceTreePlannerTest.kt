package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.AddPropertyChange
import io.mo.dtbooverclocker.core.devicetree.CloneNodeChange
import io.mo.dtbooverclocker.core.devicetree.DeleteNodeChange
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimingDeviceTreePlannerTest
{
    @Test
    fun overwriteUsesGenericPropertyChangesAndMatchesLegacySemantics()
    {
        val file = normalTimingFixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME
        )

        assertTrue(plan.operations.isNotEmpty())
        assertTrue(plan.operations.all { it is SetPropertyChange || it is AddPropertyChange })

        file.writeText(plan.replayedText)
        val updated = DtsTimingPatcher.analyzeEntry(0, file).single()
        assertEquals(144, updated.currentHz)
        assertEquals(24, updated.vFrontPorch)
        assertEquals(24, updated.vBackPorch)
    }

    @Test
    fun appendClonesTimingThroughDeviceTreeCoreAndKeepsOriginal()
    {
        val file = commandModeFixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file)
            .first { !it.hasVendorDynamicMode }

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.APPEND_NEW
        )

        assertTrue(plan.operations.first() is CloneNodeChange)
        assertTrue(plan.targetNodePath?.endsWith("timing@wqhd_normal_144hz_index_11") == true)

        file.writeText(plan.replayedText)
        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        val added = candidates.first { it.nodePath == plan.targetNodePath }

        assertEquals(144, added.currentHz)
        assertEquals(1_632_000_000L, added.pixelClockHz)
        assertEquals(6083L, added.mdpTransferTimeUs)
        assertEquals(16, added.vFrontPorch)
        assertEquals(24, added.vBackPorch)
        assertEquals(2, candidates.count { it.currentHz == 120 })
    }

    @Test
    fun deleteUpdatesNativeModeBeforeDeletingNode()
    {
        val file = File.createTempFile("timing_planner_delete", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        display-timings {
                            native-mode = <&timing0>;
                            timing0: timing@0 {
                                qcom,mdss-dsi-panel-framerate = <60>;
                                qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                            };
                            timing1: timing@1 {
                                qcom,mdss-dsi-panel-framerate = <120>;
                                qcom,mdss-dsi-panel-clockrate = <0x40000000>;
                            };
                        };
                    };
                };
                """.trimIndent()
            )
        }
        val candidate = DtsTimingPatcher.analyzeEntry(0, file)
            .first { it.currentHz == 60 }

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 60,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.DELETE_EXISTING
        )

        assertTrue(plan.operations.any {
            it is SetPropertyChange && it.propertyName == "native-mode"
        })
        assertTrue(plan.operations.last() is DeleteNodeChange)
        assertTrue(plan.replayedText.contains("native-mode = <&timing1>;"))
        assertFalse(plan.replayedText.contains("timing0: timing@0"))
    }

    @Test
    fun inheritedClockBecomesDeclaredAddPropertyOperation()
    {
        val file = File.createTempFile("timing_planner_parent_clock", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        qcom,mdss-dsi-panel-clockrate = <0x29b92700>;
                        timing@0 {
                            qcom,mdss-dsi-panel-framerate = <120>;
                            qcom,mdss-dsi-panel-width = <1440>;
                            qcom,mdss-dsi-panel-height = <3200>;
                            qcom,mdss-dsi-h-front-porch = <20>;
                            qcom,mdss-dsi-h-back-porch = <20>;
                            qcom,mdss-dsi-h-pulse-width = <4>;
                            qcom,mdss-dsi-v-front-porch = <20>;
                            qcom,mdss-dsi-v-back-porch = <20>;
                            qcom,mdss-dsi-v-pulse-width = <2>;
                        };
                    };
                };
                """.trimIndent()
            )
        }
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME
        )

        assertTrue(plan.operations.any {
            it is AddPropertyChange &&
                it.propertyName == "qcom,mdss-dsi-panel-clockrate"
        })
    }

    private fun normalTimingFixture(): File
    {
        return File.createTempFile("timing_planner_normal", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        timing@0 {
                            qcom,mdss-dsi-panel-framerate = <0x78>;
                            qcom,mdss-dsi-panel-clockrate = <0x29b92700>;
                            qcom,mdss-dsi-panel-width = <1440>;
                            qcom,mdss-dsi-panel-height = <3200>;
                            qcom,mdss-dsi-h-front-porch = <20>;
                            qcom,mdss-dsi-h-back-porch = <20>;
                            qcom,mdss-dsi-h-pulse-width = <4>;
                            qcom,mdss-dsi-v-front-porch = <20>;
                            qcom,mdss-dsi-v-back-porch = <20>;
                            qcom,mdss-dsi-v-pulse-width = <2>;
                        };
                    };
                };
                """.trimIndent()
            )
        }
    }

    private fun commandModeFixture(): File
    {
        return File.createTempFile("timing_planner_command", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        timing@wqhd_normal_120hz_index_01 {
                            qcom,mdss-dsi-panel-framerate = <120>;
                            qcom,mdss-dsi-panel-clockrate = <1360000000>;
                            qcom,mdss-mdp-transfer-time-us = <7300>;
                            qcom,mdss-dsi-v-front-porch = <16>;
                            qcom,mdss-dsi-v-back-porch = <24>;
                        };
                        timing@wqhd_auto_120_to_30hz_index_10 {
                            qcom,mdss-dsi-panel-framerate = <120>;
                            mi,mdss-dsi-ddic-mode = "auto";
                            mi,mdss-dsi-sf-framerate = <120>;
                        };
                    };
                };
                """.trimIndent()
            )
        }
    }

    @Test
    fun plannerMatchesLegacyForPixelClockOnlyCommandMode()
    {
        val source = commandModeFixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, source)
            .first { !it.hasVendorDynamicMode }

        val legacy = DtsTimingPatcher.patch(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.PIXEL_CLOCK_ONLY,
            mode = PatchMode.OVERWRITE_EXISTING
        )
        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.PIXEL_CLOCK_ONLY,
            mode = PatchMode.OVERWRITE_EXISTING
        )

        val legacyFile = File.createTempFile("legacy_pixel", ".dts").apply { writeText(legacy.text) }
        val plannerFile = File.createTempFile("planner_pixel", ".dts").apply { writeText(plan.replayedText) }
        val legacyCandidate = DtsTimingPatcher.analyzeEntry(0, legacyFile)
            .first { it.nodePath == candidate.nodePath }
        val plannerCandidate = DtsTimingPatcher.analyzeEntry(0, plannerFile)
            .first { it.nodePath == candidate.nodePath }

        assertTimingSemanticsEqual(legacyCandidate, plannerCandidate)
    }

    @Test
    fun plannerMatchesLegacyForCustomStrategy()
    {
        val source = normalTimingFixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, source).single()
        val custom = io.mo.dtbooverclocker.model.CustomTimingParams(
            pixelClockHz = 1_234_567_890L,
            vFrontPorch = 35,
            vBackPorch = 45,
            hFrontPorch = 25,
            hBackPorch = 35
        )

        val legacy = DtsTimingPatcher.patch(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.CUSTOM,
            mode = PatchMode.OVERWRITE_EXISTING,
            customParams = custom
        )
        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.CUSTOM,
            mode = PatchMode.OVERWRITE_EXISTING,
            customParams = custom
        )

        val legacyFile = File.createTempFile("legacy_custom", ".dts").apply { writeText(legacy.text) }
        val plannerFile = File.createTempFile("planner_custom", ".dts").apply { writeText(plan.replayedText) }
        assertTimingSemanticsEqual(
            DtsTimingPatcher.analyzeEntry(0, legacyFile).single(),
            DtsTimingPatcher.analyzeEntry(0, plannerFile).single()
        )
    }

    @Test
    fun plannerHandlesDtcStringClockWithoutLegacyPatchExecution()
    {
        val source = File.createTempFile("planner_string_clock", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        timing@0 {
                            qcom,mdss-dsi-panel-framerate = <144>;
                            qcom,mdss-dsi-panel-clockrate = "aFX";
                            qcom,mdss-dsi-panel-width = <1200>;
                            qcom,mdss-dsi-panel-height = <2670>;
                            qcom,mdss-dsi-h-front-porch = <20>;
                            qcom,mdss-dsi-h-back-porch = <20>;
                            qcom,mdss-dsi-h-pulse-width = <4>;
                            qcom,mdss-dsi-v-front-porch = <20>;
                            qcom,mdss-dsi-v-back-porch = <20>;
                            qcom,mdss-dsi-v-pulse-width = <2>;
                        };
                    };
                };
                """.trimIndent()
            )
        }
        val candidate = DtsTimingPatcher.analyzeEntry(0, source).single()

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 165,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME
        )

        val plannerFile = File.createTempFile("planner_string_clock_result", ".dts").apply {
            writeText(plan.replayedText)
        }
        val updated = DtsTimingPatcher.analyzeEntry(0, plannerFile).single()
        assertEquals(165, updated.currentHz)
        assertTrue(updated.pixelClockHz != null && updated.pixelClockHz > 1_632_000_000L)
        assertFalse(plan.replayedText.contains("\"aFX\""))
        assertTrue(plan.replayedText.contains("qcom,mdss-dsi-panel-clockrate = ["))
    }

    @Test
    fun plannerRejectsDynamicTemplateAndUnsafeFramerateOnlyTransferBudget()
    {
        val source = commandModeFixture()
        val candidates = DtsTimingPatcher.analyzeEntry(0, source)
        val dynamic = candidates.first { it.hasVendorDynamicMode }
        val normal = candidates.first { !it.hasVendorDynamicMode }

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TimingDeviceTreePlanner.plan(
                candidate = dynamic,
                targetHz = 144,
                strategy = PatchStrategy.PIXEL_CLOCK_ONLY
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TimingDeviceTreePlanner.plan(
                candidate = normal,
                targetHz = 144,
                strategy = PatchStrategy.FRAMERATE_ONLY
            )
        }
    }

    private fun assertTimingSemanticsEqual(
        expected: io.mo.dtbooverclocker.model.TimingCandidate,
        actual: io.mo.dtbooverclocker.model.TimingCandidate
    )
    {
        assertEquals(expected.currentHz, actual.currentHz)
        assertEquals(expected.pixelClockHz, actual.pixelClockHz)
        assertEquals(expected.hFrontPorch, actual.hFrontPorch)
        assertEquals(expected.hBackPorch, actual.hBackPorch)
        assertEquals(expected.vFrontPorch, actual.vFrontPorch)
        assertEquals(expected.vBackPorch, actual.vBackPorch)
        assertEquals(expected.mdpTransferTimeUs, actual.mdpTransferTimeUs)
    }


    @Test
    fun appendSafelyStripsTemplateRootLabelAndKeepsOriginalLabel()
    {
        val file = File.createTempFile("planner_label_clone", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        timing0: timing@0 {
                            qcom,mdss-dsi-panel-framerate = <60>;
                            qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                            qcom,mdss-dsi-panel-width = <1080>;
                            qcom,mdss-dsi-panel-height = <2400>;
                            qcom,mdss-dsi-h-front-porch = <20>;
                            qcom,mdss-dsi-h-back-porch = <20>;
                            qcom,mdss-dsi-h-pulse-width = <4>;
                            qcom,mdss-dsi-v-front-porch = <20>;
                            qcom,mdss-dsi-v-back-porch = <20>;
                            qcom,mdss-dsi-v-pulse-width = <2>;
                        };
                        timing1: timing@1 {
                            qcom,mdss-dsi-panel-framerate = <120>;
                            qcom,mdss-dsi-panel-clockrate = <0x40000000>;
                            qcom,mdss-dsi-panel-width = <1080>;
                            qcom,mdss-dsi-panel-height = <2400>;
                            qcom,mdss-dsi-h-front-porch = <20>;
                            qcom,mdss-dsi-h-back-porch = <20>;
                            qcom,mdss-dsi-h-pulse-width = <4>;
                            qcom,mdss-dsi-v-front-porch = <20>;
                            qcom,mdss-dsi-v-back-porch = <20>;
                            qcom,mdss-dsi-v-pulse-width = <2>;
                        };
                    };
                };
                """.trimIndent()
            )
        }
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).first { it.currentHz == 120 }

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.APPEND_NEW
        )

        assertTrue(plan.replayedText.contains("timing1: timing@1 {"))
        assertTrue(plan.replayedText.contains("timing@2 {"))
        assertFalse(plan.replayedText.contains("timing1: timing@2 {"))
    }

}
