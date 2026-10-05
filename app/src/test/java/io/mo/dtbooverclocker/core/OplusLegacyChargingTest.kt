package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OplusLegacyChargingTest {
    // Reduced from realme RMX3823 dtbo (issue #6), entry 0 `&battery_charger` overlay.
    private val source = """
        /dts-v1/;
        / {
            fragment@50 {
                target = <0xffffffff>;
                __overlay__ {
                    oplus,chg_ops = "plat-pmic";
                    qcom,vooc_project = <16>;
                    qcom,input_current_charger_ma = <2000>;
                    qcom,iterm_ma = <122>;
                    qcom,recharge-mv = <100>;
                    qcom,batt_capacity_mah = <4600>;
                    qcom,cold_bat_decidegc = <200>;
                    qcom,temp_cold_fastchg_current_ma_high = <600>;
                    qcom,temp_normal_fastchg_current_ma = <2200>;
                    qcom,pd_temp_normal_fastchg_current_ma = <4000>;
                    qcom,temp_normal_vfloat_mv = <4435>;
                    qcom,normal_vfloat_sw_limit = <4385>;
                    qcom,normal_vfloat_over_sw_limit = <4445>;
                    qcom,vbatt_hv_thr = <4600>;
                    qcom,charger_hv_thr = <9900>;
                    qcom,charger_recv_thr = <9500>;
                    qcom,ffc_temp_warm_vfloat_mv = <4500>;
                    qcom,usbtemp_batt_temp_low = <50>;
                    qcom,chargerid_switch-gpio = <69 2 0>;
                };
            };
            fragment@23 {
                target = <0xffffffff>;
                __overlay__ {
                    qcom,iterm_ma = <122>;
                    qcom,thermal-mitigation = <3000000 1500000>;
                };
            };
            __fixups__ {
                battery_charger = "/fragment@50:target:0", "/fragment@23:target:0";
            };
        };
    """.trimIndent()

    private fun node(text: String = source, path: String = "/fragment@50/__overlay__") =
        ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(0, text))).single { it.nodePath == path }

    @Test fun exposesOnlyDriverVerifiedScalars() {
        val node = node()
        assertEquals(
            setOf(
                "qcom,input_current_charger_ma", "qcom,iterm_ma", "qcom,recharge-mv",
                "qcom,temp_cold_fastchg_current_ma_high", "qcom,temp_normal_fastchg_current_ma",
                "qcom,pd_temp_normal_fastchg_current_ma", "qcom,temp_normal_vfloat_mv",
                "qcom,normal_vfloat_sw_limit", "qcom,normal_vfloat_over_sw_limit",
                "qcom,vbatt_hv_thr", "qcom,charger_hv_thr", "qcom,charger_recv_thr"
            ),
            node.fields.filter { it.issue == null }.map { it.parameter.name }.toSet()
        )
        // Capacity, temperatures, unparsed FFC-warm variants and GPIOs stay read-only.
        listOf("qcom,batt_capacity_mah", "qcom,cold_bat_decidegc", "qcom,ffc_temp_warm_vfloat_mv", "qcom,chargerid_switch-gpio")
            .forEach { name -> assertTrue(name, node.otherProperties.any { it.first == name }) }
        assertEquals("mV", node.fields.single { it.parameter.name == "qcom,normal_vfloat_sw_limit" }.parameter.unit)
        assertEquals("常温区 PD 充电电流", node.fields.single { it.parameter.name == "qcom,pd_temp_normal_fastchg_current_ma" }.parameter.label)
        assertEquals("寒冷区 充电电流 · 4.18V 以下", node.fields.single { it.parameter.name == "qcom,temp_cold_fastchg_current_ma_high" }.parameter.label)
    }

    @Test fun requiresTheOplusChargerSignature() {
        // `qcom,iterm_ma` alone does not identify the oplus_charger.c binding.
        assertFalse(node(path = "/fragment@23/__overlay__").fields.any { it.parameter.name == "qcom,iterm_ma" })
    }

    @Test fun enforcesVoltageOrdering() {
        val node = node()
        assertThrows(IllegalArgumentException::class.java) {
            ChargingPlanner.preview(node, mapOf("qcom,temp_normal_vfloat_mv" to "4450"))
        }.also { assertTrue(it.message!!.contains("过压降档")) }
        assertThrows(IllegalArgumentException::class.java) {
            ChargingPlanner.preview(node, mapOf("qcom,normal_vfloat_sw_limit" to "4440"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChargingPlanner.preview(node, mapOf("qcom,normal_vfloat_over_sw_limit" to "4650"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChargingPlanner.preview(node, mapOf("qcom,charger_recv_thr" to "10000"))
        }
        // Raising the whole chain together is consistent.
        assertEquals(3, ChargingPlanner.preview(node, mapOf(
            "qcom,normal_vfloat_sw_limit" to "4400",
            "qcom,temp_normal_vfloat_mv" to "4450",
            "qcom,normal_vfloat_over_sw_limit" to "4460"
        )).values.size)
    }

    @Test fun editsScalarAndPreservesEverythingElse() {
        val before = node()
        val plan = ChargingPlanner.plan(source, before, mapOf("qcom,temp_normal_fastchg_current_ma" to "2500", "qcom,iterm_ma" to "150"))
        val after = node(plan.replayedText)
        assertEquals(2500L, after.fields.single { it.parameter.name == "qcom,temp_normal_fastchg_current_ma" }.value)
        assertEquals(150L, after.fields.single { it.parameter.name == "qcom,iterm_ma" }.value)
        assertEquals(before.otherProperties, after.otherProperties)
        val reverted = plan.transaction.operations.reversed().fold(plan.replayedText) { text, op -> DeviceTreeEditor.apply(text, op.inverse()) }
        assertEquals(before, node(reverted))
    }

    @Test fun realmeImageExposesBothChargingVariants() {
        val path = System.getProperty("dtbo.oplusLegacySampleImage", "").orEmpty()
        assumeTrue("Run with -PoplusLegacySampleImage=<dtbo.img>", path.isNotBlank())
        val documents = DtboImageCodec.parse(File(path)).entries.mapIndexed { index, entry ->
            DeviceTreeParser.parse(index, FdtTestRenderer.render(entry.decodedBytes))
        }
        val nodes = ChargingAnalyzer.analyze(documents).filter { node -> node.fields.any { it.group.startsWith("OPlus 旧版") } }
        assertEquals((0..5).toList(), nodes.map { it.entryIndex })
        nodes.forEach { node ->
            assertTrue(node.fields.all { it.issue == null })
            println("DTB[${node.entryIndex}] ${node.nodePath}: ${node.editableCount} editable, ${node.otherProperties.size} read-only")
        }
        val iterm = nodes.associate { node -> node.entryIndex to node.fields.single { it.parameter.name == "qcom,iterm_ma" }.value }
        assertEquals(mapOf(0 to 122L, 1 to 135L, 2 to 122L, 3 to 135L, 4 to 135L, 5 to 122L), iterm)
        // The shipped values already satisfy every ordering rule the planner enforces.
        nodes.forEach { node ->
            val values = node.fields.associate { it.parameter.name to it.value }
            OplusLegacyChargingBindings.orderedPairs.forEach { (low, high, message) ->
                val a = values[low] ?: return@forEach
                val b = values[high] ?: return@forEach
                assertTrue("DTB[${node.entryIndex}] $message: $a > $b", a!! <= b!!)
            }
        }
    }
}
