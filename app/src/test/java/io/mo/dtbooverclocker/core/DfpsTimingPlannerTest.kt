package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 面板参数取自一加 Pad 3（issue #8）DTB[5] 的 XN242 视频模式面板。 */
class DfpsTimingPlannerTest {
    private val panelPath = "/fragment@104/__overlay__/qcom,mdss_dsi_panel_XN242_P_D_A0024_vid"

    private fun fixture(
        update: String = "dfps_immediate_porch_mode_both_hv_porch",
        rates: String = "0x78 0x90 0x5a 0x3c 0x32 0x30 0x1e",
        extraPanel: String = "",
        extraTiming: String = ""
    ) = File.createTempFile("dfps", ".dts").apply {
        writeText(
            """/dts-v1/;
                |/ {
                |    fragment@104 {
                |        __overlay__ {
                |            qcom,mdss_dsi_panel_XN242_P_D_A0024_vid {
                |                qcom,dsi-dfps-vpw-list = <0x2 0x2 0x2 0x2 0x2 0x2 0x2>;
                |                qcom,dsi-dfps-vfp-list = <0x1a 0x1a 0x1a 0xa7c 0x7da 0x894 0x1f40>;
                |                qcom,dsi-dfps-vbp-list = <0xe6 0x34 0x34 0xe6 0x34 0x34 0xe6>;
                |                qcom,dsi-dfps-hpw-list = <0x20 0x10 0x50 0x20 0x50 0x50 0x20>;
                |                qcom,dsi-dfps-hbp-list = <0x40 0x14 0xa0 0x40 0xa0 0xa0 0x40>;
                |                qcom,dsi-dfps-hfp-list = <0x32 0x22 0xd2 0x32 0xd2 0xd2 0x32>;
                |                qcom,dsi-supported-dfps-list = <$rates>;
                |                qcom,mdss-dsi-pan-fps-update = "$update";
                |                qcom,mdss-dsi-pan-enable-dynamic-fps;
                |$extraPanel
                |                qcom,mdss-dsi-panel-type = "dsi_video_mode";
                |                qcom,mdss-dsi-display-timings {
                |                    timing@0 {
                |$extraTiming
                |                        qcom,mdss-dsi-panel-clockrate = <0x5136ec5b>;
                |                        qcom,mdss-dsi-panel-framerate = <0x78>;
                |                        qcom,mdss-dsi-v-pulse-width = <0x2>;
                |                        qcom,mdss-dsi-v-front-porch = <0x1a>;
                |                        qcom,mdss-dsi-v-back-porch = <0xe6>;
                |                        qcom,mdss-dsi-h-pulse-width = <0x20>;
                |                        qcom,mdss-dsi-h-back-porch = <0x40>;
                |                        qcom,mdss-dsi-h-front-porch = <0x32>;
                |                        qcom,mdss-dsi-panel-height = <0x960>;
                |                        qcom,mdss-dsi-panel-width = <0x6a0>;
                |                    };
                |                };
                |            };
                |        };
                |    };
                |};
            """.trimMargin()
        )
    }

    private fun panelAfter(file: File, text: String) = DfpsTimingPlanner.readPanel(
        DeviceTreeParser.parse(0, text), DtsTimingPatcher.analyzeEntry(0, file).single()
    )

    @Test
    fun appendAbovePeakRaisesClockAndKeepsExistingRefreshRates() {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val before = panelAfter(file, file.readText())
        val plan = DfpsTimingPlanner.plan(candidate, 165, replaceHz = null)
        val after = panelAfter(file, plan.replayedText)

        assertEquals(listOf(120, 144, 90, 60, 50, 48, 30, 165), after.rates)
        val scale = after.clockHz!!.toDouble() / before.clockHz!!
        assertEquals(165.0 / 144, scale, 1e-6)
        // 时钟按比例提高后，原有档位的帧长按同一比例变长，刷新率不变（行数取整误差 < 0.1%）。
        before.rates.indices.forEach { i ->
            assertEquals("DFPS[$i]", scale, after.vTotal(i).toDouble() / before.vTotal(i), 1e-3)
        }
        // 新档位沿用 144 Hz 档的 porch 与帧长：165 Hz 的像素吞吐与原 144 Hz 档相同。
        val newIndex = after.rates.indexOf(165)
        assertEquals(before.vTotal(1), after.vTotal(newIndex))
        listOf("qcom,dsi-dfps-hfp-list", "qcom,dsi-dfps-hbp-list", "qcom,dsi-dfps-hpw-list",
            "qcom,dsi-dfps-vbp-list", "qcom,dsi-dfps-vpw-list").forEach { name ->
            assertEquals(name, before.list(name) + before.list(name)[1], after.list(name))
        }
        assertEquals(before.vFrontPorch + after.baseVTotal - before.baseVTotal, after.vFrontPorch)
        assertTrue(plan.warnings.any { "DSI 时钟提高" in it })
    }

    @Test
    fun replaceKeepsListLengthAndRejectsDefaultMode() {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val plan = DfpsTimingPlanner.plan(candidate, 165, replaceHz = 144)
        val after = panelAfter(file, plan.replayedText)
        assertEquals(listOf(120, 165, 90, 60, 50, 48, 30), after.rates)
        assertTrue(after.lists.values.all { it.size == 7 })

        val error = assertThrows(IllegalArgumentException::class.java) {
            DfpsTimingPlanner.plan(candidate, 165, replaceHz = 120)
        }
        assertTrue(error.message!!.contains("开机默认模式"))
        assertThrows(IllegalArgumentException::class.java) { DfpsTimingPlanner.plan(candidate, 90, replaceHz = null) }
    }

    @Test
    fun rateBelowPeakKeepsClock() {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val plan = DfpsTimingPlanner.plan(candidate, 100, replaceHz = null)
        assertTrue(plan.operations.none { it.propertyName == "qcom,mdss-dsi-panel-clockrate" })
        val after = panelAfter(file, plan.replayedText)
        // 固定时钟下 100 Hz 的帧长 = 144 Hz 档帧长 × 144 / 100。
        assertEquals(2480 * 144 / 100.0, after.vTotal(after.rates.indexOf(100)).toDouble(), 1.0)
    }

    @Test
    fun qsyncListIsExtendedWithReferenceValue() {
        val file = fixture(extraPanel = "                qcom,dsi-supported-qsync-min-fps-list = <0x3c 0x48 0x3c 0x3c 0x32 0x30 0x1e>;")
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val after = panelAfter(file, DfpsTimingPlanner.plan(candidate, 165, null).replayedText)
        assertEquals(listOf(60L, 72, 60, 60, 50, 48, 30, 72), after.list("qcom,dsi-supported-qsync-min-fps-list"))
    }

    @Test
    fun vfpModeScalesBaseTiming() {
        // 典型 VFP 模式面板：基准时序即最高档 120 Hz，其余档位由内核加大 VFP 得到。
        val file = fixture(update = "dfps_immediate_porch_mode_vfp", rates = "0x78 0x5a 0x3c")
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val before = panelAfter(file, file.readText())
        val plan = DfpsTimingPlanner.plan(candidate, 144, null)
        val after = panelAfter(file, plan.replayedText)
        assertTrue(after.lists.isEmpty())
        assertEquals(listOf(120, 90, 60, 144), after.rates)
        // 新档位帧长回到原 120 Hz 的帧长，原有档位帧长随时钟同比加长。
        assertTrue(abs(after.vTotal(3) - before.baseVTotal) <= 2)
        (0..2).forEach { i -> assertEquals(1.2, after.vTotal(i).toDouble() / before.vTotal(i), 1e-3) }
        val clock = DtsNumericValueCodec.decode("<0x5136ec5b>")!!
        assertEquals(clock * 1.2, after.clockHz!!.toDouble(), 1.0)
    }

    @Test
    fun unsupportedConfigurationsAreRejected() {
        val commands = fixture(extraTiming = "                        qcom,mdss-dsi-dfps-commands {\n                        };")
        assertThrows(IllegalArgumentException::class.java) {
            DfpsTimingPlanner.plan(DtsTimingPatcher.analyzeEntry(0, commands).single(), 165, null)
        }
        val hfp = fixture(update = "dfps_immediate_porch_mode_hfp")
        val error = runCatching { DfpsTimingPlanner.plan(DtsTimingPatcher.analyzeEntry(0, hfp).single(), 165, null) }
        assertTrue(error.exceptionOrNull()!!.message!!.contains("暂不支持"))
        assertNull(error.getOrNull())
    }

    @Test
    fun operationsOnlyTouchPanelListsClockAndBaseVfp() {
        val file = fixture()
        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val plan = DfpsTimingPlanner.plan(candidate, 165, null)
        val touched = plan.operations.map { "${it.nodePath}/${it.propertyName}" }.toSet()
        val timing = "$panelPath/qcom,mdss-dsi-display-timings/timing@0"
        assertEquals(
            setOf(
                "$panelPath/qcom,dsi-supported-dfps-list", "$panelPath/qcom,dsi-dfps-hfp-list",
                "$panelPath/qcom,dsi-dfps-hbp-list", "$panelPath/qcom,dsi-dfps-hpw-list",
                "$panelPath/qcom,dsi-dfps-vbp-list", "$panelPath/qcom,dsi-dfps-vfp-list",
                "$panelPath/qcom,dsi-dfps-vpw-list", "$timing/qcom,mdss-dsi-panel-clockrate",
                "$timing/qcom,mdss-dsi-v-front-porch"
            ),
            touched
        )
    }
}
