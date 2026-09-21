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
}
