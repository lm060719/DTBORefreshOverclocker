package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.model.ResolutionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ResolutionPlannerTest
{
    @Test
    fun matchingGroupUpdatesGeometryDscAndRecognizedFullWidthRoi()
    {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file)
            .first { it.currentHz == 120 }

        val plan = ResolutionPlanner.plan(
            candidate = candidate,
            targetWidth = 1080,
            targetHeight = 2400,
            scope = ResolutionScope.MATCHING_GROUP
        )

        assertEquals(2, plan.affectedNodePaths.size)
        assertTrue(plan.operations.size >= 8)
        val document = DeviceTreeParser.parse(0, plan.replayedText)

        plan.affectedNodePaths.forEach { path ->
            val node = requireNotNull(document.findNode(path))
            assertEquals(1080L, number(node.properties.first { it.name == "qcom,mdss-dsi-panel-width" }.rawValue))
            assertEquals(2400L, number(node.properties.first { it.name == "qcom,mdss-dsi-panel-height" }.rawValue))
            assertEquals(540L, number(node.properties.first { it.name == "qcom,mdss-dsc-slice-width" }.rawValue))
            assertEquals(
                listOf(1080L, 20L, 1080L, 20L, 1080L, 20L),
                DtsNumericValueCodec.decodeCells(
                    node.properties.first { it.name == "qcom,panel-roi-alignment" }.rawValue
                )
            )
        }
    }

    @Test
    fun selectedTimingOnlyDoesNotTouchSibling()
    {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file)
            .first { it.currentHz == 120 }

        val plan = ResolutionPlanner.plan(
            candidate = candidate,
            targetWidth = 720,
            targetHeight = 1600,
            scope = ResolutionScope.SELECTED_TIMING
        )

        assertEquals(listOf(candidate.nodePath), plan.affectedNodePaths)
        val document = DeviceTreeParser.parse(0, plan.replayedText)
        val changed = requireNotNull(document.findNode(candidate.nodePath))
        val untouched = requireNotNull(document.findNode(candidate.nodePath.replace("120hz", "60hz")))
        assertEquals(720L, number(changed.properties.first { it.name == "qcom,mdss-dsi-panel-width" }.rawValue))
        assertEquals(1440L, number(untouched.properties.first { it.name == "qcom,mdss-dsi-panel-width" }.rawValue))
    }

    @Test
    fun rejectsAspectRatioChangeAndUnsafeDscDivisibility()
    {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).first()

        assertThrows(IllegalArgumentException::class.java) {
            ResolutionPlanner.plan(candidate, 1080, 2500, ResolutionScope.MATCHING_GROUP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResolutionPlanner.plan(candidate, 999, 2220, ResolutionScope.MATCHING_GROUP)
        }
    }

    @Test
    fun rejectsUnknownRoiLayoutInsteadOfGuessing()
    {
        val file = fixture().apply {
            writeText(readText().replace(
                "qcom,panel-roi-alignment = <0x5a0 0x14 0x5a0 0x14 0x5a0 0x14>;",
                "qcom,panel-roi-alignment = <0x2 0x2 0x2 0x2 0x2 0x2>;"
            ))
        }
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).first()

        assertThrows(IllegalArgumentException::class.java) {
            ResolutionPlanner.plan(candidate, 1080, 2400, ResolutionScope.MATCHING_GROUP)
        }
    }

    private fun number(raw: String?): Long
    {
        return requireNotNull(DtsNumericValueCodec.decode(raw))
    }

    private fun fixture(): File
    {
        return File.createTempFile("resolution_planner", ".dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        timings {
                            timing@wqhd_normal_60hz {
                                qcom,mdss-dsi-panel-framerate = <60>;
                                qcom,mdss-dsi-panel-width = <0x5a0>;
                                qcom,mdss-dsi-panel-height = <0xc80>;
                                qcom,compression-mode = "dsc";
                                qcom,mdss-dsc-slice-per-pkt = <0x2>;
                                qcom,mdss-dsc-slice-width = <0x2d0>;
                                qcom,mdss-dsc-slice-height = <0x14>;
                                qcom,panel-roi-alignment = <0x5a0 0x14 0x5a0 0x14 0x5a0 0x14>;
                            };
                            timing@wqhd_normal_120hz {
                                qcom,mdss-dsi-panel-framerate = <120>;
                                qcom,mdss-dsi-panel-width = <0x5a0>;
                                qcom,mdss-dsi-panel-height = <0xc80>;
                                qcom,compression-mode = "dsc";
                                qcom,mdss-dsc-slice-per-pkt = <0x2>;
                                qcom,mdss-dsc-slice-width = <0x2d0>;
                                qcom,mdss-dsc-slice-height = <0x14>;
                                qcom,panel-roi-alignment = <0x5a0 0x14 0x5a0 0x14 0x5a0 0x14>;
                            };
                            timing@fhd_normal_60hz {
                                qcom,mdss-dsi-panel-framerate = <60>;
                                qcom,mdss-dsi-panel-width = <0x438>;
                                qcom,mdss-dsi-panel-height = <0x960>;
                                qcom,compression-mode = "dsc";
                                qcom,mdss-dsc-slice-per-pkt = <0x2>;
                                qcom,mdss-dsc-slice-width = <0x21c>;
                                qcom,mdss-dsc-slice-height = <0x14>;
                                qcom,panel-roi-alignment = <0x438 0x14 0x438 0x14 0x438 0x14>;
                            };
                        };
                    };
                };
                """.trimIndent()
            )
        }
    }
}
