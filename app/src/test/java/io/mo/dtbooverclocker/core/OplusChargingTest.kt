package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OplusChargingTest
{
    // Reduced from the supplied OPlus / OnePlus dtbo_a.img. The binary image is not checked into the repository.
    private val source = """
        /dts-v1/;
        / {
            comm {
                compatible = "oplus,common-charge";
                oplus_spec,iterm-ma = <188>;
                oplus_spec,fcc-gear-thr-mv = <4180>;
                oplus_spec,vbatt-ov-thr-mv = <4600>;
                oplus_spec,vbat_uv_thr_mv = <2750>;
                oplus_spec,wired-ffc-fcc-ma = <550 550 550 350 350 350>;
            };

            pps {
                compatible = "oplus,pps_charge";
                oplus,curr_max_ma = <5000>;
                oplus,pps_strategy_high_current = <5000 4000 3000 5000 5000 4000>;
            };

            ufcs {
                compatible = "oplus,ufcs_charge";
                oplus,curr_max_ma = <10900>;
                oplus,ufcs_strategy_high_current = <6000 5000 3000 10900 6000 5000>;
            };

            cp {
                compatible = "oplus,virtual_cp";
                oplus,input_curr_max_ma = <13700>;
            };

            wls {
                compatible = "oplus,chg_wls";
                oplus,max-voltage-mv = <4580>;
                oplus,fastchg_curr_max_ma = <5000>;
                oplus,verity_curr_max_ma = <5000>;
                oplus,wls_boost_curr_limit_ma = <1500>;
                oplus,bpp-vol-mv = <5500>;
                oplus,epp-vol-mv = <6000>;
                oplus,cp-open-offset-mv = <150>;
                oplus,cp-open-offset-min-mv = <50>;
                oplus,iclmax-ma = <250 500 750 1000 1250 1500>;
            };

            unknown_charger {
                compatible = "vendor,charger";
                oplus,max-voltage-mv = <9999>;
            };
        };
    """.trimIndent()

    private fun nodes(text: String = source) =
        ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(0, text)))

    private fun node(name: String, text: String = source) =
        nodes(text).single { it.nodePath == "/$name" }

    @Test
    fun exposesOnlyVerifiedOplusScalarBindings()
    {
        assertEquals(
            setOf(
                "oplus_spec,iterm-ma",
                "oplus_spec,fcc-gear-thr-mv",
                "oplus_spec,vbatt-ov-thr-mv",
                "oplus_spec,vbat_uv_thr_mv"
            ),
            node("comm").fields.filter { it.issue == null }.map { it.parameter.name }.toSet()
        )

        assertEquals(1, node("pps").editableCount)
        assertEquals("5000", ChargingAnalyzer.displayValue(node("pps").fields.single()))
        assertEquals(1, node("ufcs").editableCount)
        assertEquals(1, node("cp").editableCount)
        assertEquals(8, node("wls").editableCount)

        assertTrue(node("comm").otherProperties.any { it.first == "oplus_spec,wired-ffc-fcc-ma" })
        assertTrue(node("wls").otherProperties.any { it.first == "oplus,iclmax-ma" })
    }

    @Test
    fun requiresExactOplusCompatible()
    {
        val unknown = node("unknown_charger")
        assertEquals(0, unknown.editableCount)
        assertTrue(unknown.otherProperties.any { it.first == "oplus,max-voltage-mv" })
    }

    @Test
    fun editsScalarAndPreservesOpaqueStrategyTables()
    {
        val before = node("wls")
        val plan = ChargingPlanner.plan(
            source,
            before,
            mapOf(
                "oplus,fastchg_curr_max_ma" to "4500",
                "oplus,cp-open-offset-min-mv" to "60"
            )
        )

        val after = node("wls", plan.replayedText)
        assertEquals(
            4500L,
            after.fields.single { it.parameter.name == "oplus,fastchg_curr_max_ma" }.value
        )
        assertEquals(
            60L,
            after.fields.single { it.parameter.name == "oplus,cp-open-offset-min-mv" }.value
        )

        val originalTable = DeviceTreeParser.parse(0, source)
            .findNode("/wls")!!
            .properties.single { it.name == "oplus,iclmax-ma" }.rawValue
        val updatedTable = DeviceTreeParser.parse(0, plan.replayedText)
            .findNode("/wls")!!
            .properties.single { it.name == "oplus,iclmax-ma" }.rawValue
        assertEquals(originalTable, updatedTable)
        assertEquals(2, plan.transaction.operationCount)
    }

    @Test
    fun enforcesChargingPumpOffsetRelationship()
    {
        assertThrows(IllegalArgumentException::class.java) {
            ChargingPlanner.plan(
                source,
                node("wls"),
                mapOf("oplus,cp-open-offset-min-mv" to "200")
            )
        }
    }

    @Test
    fun boundArraysStayReadOnlyInsteadOfBeingFlattenedIntoScalarEditors()
    {
        val changed = source.replace(
            "oplus,curr_max_ma = <5000>;",
            "oplus,curr_max_ma = <5000 4500>;"
        )
        val field = node("pps", changed).fields.single { it.parameter.name == "oplus,curr_max_ma" }
        assertNotNull(field.issue)
        assertNull(field.value)
        assertEquals(0, node("pps", changed).editableCount)
        assertTrue(field.issue!!.contains("单个 U32"))
    }

    @Test
    fun suppliedScalarShapesRoundTripAsU32()
    {
        val wls = node("wls")
        wls.fields.filter { it.issue == null }.forEach { field ->
            val raw = wls.otherProperties.firstOrNull { it.first == field.parameter.name }?.second
            assertNull(raw)
            assertNotNull(DtsNumericValueCodec.decodeU32(field.rawValue))
        }
    }
}
