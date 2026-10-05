package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.AddPropertyChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.ui.components.TimingUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 命令模板：用同级档位的面板命令与时序原地覆盖目标档位（真我 GT Neo5 的 90 → 165 改法）。 */
class TimingTemplatePlannerTest
{
    private val timings = "/panel/qcom,mdss-dsi-display-timings"

    @Test
    fun templateCopiesCommandsInPlaceAndScalesFromTemplate()
    {
        val file = fixture()
        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        val target = candidates.single { it.currentHz == 90 }
        val template = TimingUtils.resolveTemplate(candidates, target, "timing@fhd_sdc_144")

        val plan = TimingDeviceTreePlanner.plan(
            candidate = target,
            targetHz = 165,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            template = template
        )

        // 不新增任何属性或节点，镜像体积才不会变大。
        assertTrue(plan.operations.all { it is SetPropertyChange })
        assertTrue(plan.operations.none { it is AddPropertyChange })

        val document = DeviceTreeParser.parse(0, plan.replayedText)
        val replaced = requireNotNull(document.findNode("$timings/timing@fhd_sdc_90"))
        fun raw(name: String) = replaced.properties.single { it.name == name }.rawValue
        assertEquals("<165>", raw("qcom,mdss-dsi-panel-framerate"))
        assertEquals("<1315416667>", raw("qcom,mdss-dsi-panel-clockrate"))
        assertEquals("<4364>", raw("qcom,mdss-mdp-transfer-time-us"))
        assertEquals("<48>", raw("qcom,mdss-dsi-h-back-porch"))
        assertEquals("[15 00 00 00 00 00 02 2F 02]", raw("qcom,mdss-dsi-timing-switch-command"))
        assertEquals("[15 00 00 00 00 00 02 BA 22]", raw("qcom,mdss-dsi-on-command"))
        assertNull(replaced.properties.firstOrNull { it.name == "qcom,mdss-dsi-qsync-on-commands" })
        assertTrue(plan.warnings.any { it.contains("qcom,mdss-dsi-qsync-on-commands") })

        // 模板档位保持原样，档位集合不变。
        file.writeText(plan.replayedText)
        val after = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(listOf(60, 144, 165), after.map { it.currentHz }.sorted())
        assertEquals(1148000000L, after.single { it.currentHz == 144 }.pixelClockHz)
    }

    @Test
    fun templateIsRejectedOutsideOverwriteMode()
    {
        val file = fixture()
        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        val target = candidates.single { it.currentHz == 90 }
        val template = candidates.single { it.currentHz == 144 }

        assertThrows(IllegalArgumentException::class.java) {
            TimingDeviceTreePlanner.plan(
                candidate = target,
                targetHz = 165,
                strategy = PatchStrategy.BALANCED_BLANKING_TIME,
                mode = PatchMode.APPEND_NEW,
                template = template
            )
        }
    }

    @Test
    fun templateSiblingsStayWithinSameDisplayTimings()
    {
        val candidates = DtsTimingPatcher.analyzeEntry(0, fixture())
        val target = candidates.single { it.currentHz == 90 }

        assertEquals(
            listOf("timing@fhd_sdc_60", "timing@fhd_sdc_144"),
            TimingUtils.findTemplateSiblings(candidates, target).map { TimingUtils.parseTimingNodeName(it.nodePath) }
        )
        assertThrows(IllegalArgumentException::class.java) {
            TimingUtils.resolveTemplate(candidates, target, "timing@missing")
        }
    }

    private fun fixture(): File = File.createTempFile("timing_template", ".dts").apply {
        fun timing(name: String, hz: Int, clock: Long, hbp: Int, transfer: Int, mode: String, extra: String = "") = """
                        $name {
                            qcom,mdss-dsi-panel-framerate = <$hz>;
                            qcom,mdss-dsi-panel-clockrate = <$clock>;
                            qcom,mdss-dsi-panel-width = <1240>;
                            qcom,mdss-dsi-panel-height = <2772>;
                            qcom,mdss-dsi-h-front-porch = <52>;
                            qcom,mdss-dsi-h-back-porch = <$hbp>;
                            qcom,mdss-dsi-h-pulse-width = <4>;
                            qcom,mdss-dsi-v-front-porch = <20>;
                            qcom,mdss-dsi-v-back-porch = <18>;
                            qcom,mdss-dsi-v-pulse-width = <2>;
                            qcom,mdss-mdp-transfer-time-us = <$transfer>;
                            qcom,mdss-dsi-timing-switch-command = [15 00 00 00 00 00 02 2F $mode];
                            qcom,mdss-dsi-on-command = [15 00 00 00 00 00 02 BA ${if (mode == "02") "22" else "11"}];$extra
                        };"""
        writeText(
            """
            /dts-v1/;
            / {
                panel {
                    qcom,mdss-dsi-display-timings {
${timing("timing@fhd_sdc_60", 60, 998400000, 48, 6000, "00")}
${timing("timing@fhd_sdc_90", 90, 998400000, 49, 9000, "01")}
${timing("timing@fhd_sdc_144", 144, 1148000000, 48, 5000, "02", "\n                            qcom,mdss-dsi-qsync-on-commands;")}
                    };
                };
            };
            """.trimIndent()
        )
    }
}
