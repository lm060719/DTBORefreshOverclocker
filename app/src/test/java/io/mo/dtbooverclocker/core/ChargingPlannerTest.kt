package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.*
import io.mo.dtbooverclocker.ui.MainUiState
import org.junit.Assert.*
import org.junit.Test

class ChargingPlannerTest {
    private val source = """
        /dts-v1/;
        / {
            charger {
                compatible = "qcom,qpnp-smb5";
                qcom,fcc-max-ua = <3000000>;
                qcom,fv-max-uv = <0x432380>;
                qcom,usb-icl-ua = [00 2d c6 c0];
                qcom,auto-recharge-vbat-mv = <4200>;
                qcom,auto-recharge-soc = <95>;
                qcom,hvdcp-disable;
                qcom,thermal-mitigation = <3000000 2000000 1000000>;
                monitored-battery = <0x12>;
            };
            battery {
                compatible = "simple-battery";
                constant-charge-current-max-microamp = <2000000>;
                constant-charge-voltage-max-microvolt = <4400000>;
                charge-term-current-microamp = <100000>;
                precharge-current-microamp = <200000>;
                precharge-upper-limit-microvolt = <3000000>;
                over-voltage-threshold-microvolt = <4500000>;
            };
            display {
                clock-frequency = <12345>;
            };
        };
    """.trimIndent()

    private fun nodes(text: String = source, entry: Int = 2) = ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(entry, text)))
    private fun charger(text: String = source) = nodes(text).first { it.nodePath == "/charger" }
    private fun properties(text: String) = DeviceTreeParser.parse(2, text).flatten().flatMap { node ->
        node.properties.map { "${node.path}/${it.name}" to it.rawValue }
    }.toMap()
    private fun fails(message: String? = null, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected rejection, got $failure", failure is IllegalArgumentException)
        if (message != null) assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains(message))
    }

    @Test fun editsSelectedEntryWithExactUnitsAndPreservesUnrelatedData() {
        val plan = ChargingPlanner.plan(source, charger(), mapOf("qcom,fcc-max-ua" to "2800.123", "qcom,usb-icl-ua" to "2500"))
        val fields = charger(plan.replayedText).fields.associateBy { it.parameter.name }
        assertEquals(2800123L, fields.getValue("qcom,fcc-max-ua").value)
        assertEquals(2500000L, fields.getValue("qcom,usb-icl-ua").value)
        assertEquals("[00 26 25 a0]", fields.getValue("qcom,usb-icl-ua").rawValue)
        assertEquals(DeviceTreeTransactionKind.CHARGING, plan.transaction.kind)
        assertEquals(FeatureModuleKind.CHARGING, plan.transaction.moduleChange?.module)
        assertEquals(DeviceTreeTransactionRisk.EXPORT_ONLY, plan.transaction.risk)
        assertFalse(plan.transaction.directFlashAllowed)
        assertEquals(setOf(2), plan.transaction.entryIndices)
        val changed = plan.transaction.operations.mapNotNull { it.allowedPropertyPath() }.toSet()
        assertEquals(properties(source).filterKeys { it !in changed }, properties(plan.replayedText).filterKeys { it !in changed })
        val state = MainUiState(transactions = listOf(plan.transaction))
        assertEquals(plan.transaction.operations, state.moduleDeviceTreeChanges)
        assertEquals(listOf(plan.transaction.moduleChange), state.moduleStagedChanges)
        assertTrue(state.deviceTreeChanges.isEmpty())
    }

    @Test fun togglesProtocolsAndUndoRestoresExactPropertyValues() {
        val plan = ChargingPlanner.plan(source, charger(), mapOf("qcom,hvdcp-disable" to "false", "qcom,usb-pd-disable" to "true"))
        assertTrue(plan.transaction.operations[0] is DeletePropertyChange)
        assertTrue(plan.transaction.operations[1] is AddPropertyChange)
        val restored = plan.transaction.operations.asReversed().fold(plan.replayedText) { text, change -> DeviceTreeEditor.apply(text, change.inverse()) }
        assertEquals(properties(source), properties(restored))
    }

    @Test fun sequentialTransactionsReplayAndUndoIndependently() {
        val first = ChargingPlanner.plan(source, charger(), mapOf("qcom,fcc-max-ua" to "2800"))
        val second = ChargingPlanner.plan(first.replayedText, charger(first.replayedText), mapOf("qcom,fcc-max-ua" to "2700", "qcom,fv-max-uv" to "4350"))
        val transactions = listOf(first.transaction, second.transaction)
        assertEquals(second.replayedText, transactions.allOperations().fold(source, DeviceTreeEditor::apply))
        val undoSecond = second.transaction.operations.asReversed().fold(second.replayedText) { text, change -> DeviceTreeEditor.apply(text, change.inverse()) }
        assertEquals(properties(first.replayedText), properties(undoSecond))
        assertEquals(setOf(2), transactions.modifiedEntryIndices())
    }

    @Test fun rejectsStaleSnapshotsAndUnknownOrMalformedProperties() {
        val snapshot = charger()
        fails("已变化") { ChargingPlanner.plan(source.replace("3000000>;", "2500000>;"), snapshot, mapOf("qcom,fcc-max-ua" to "2800")) }
        fails("不支持") { ChargingPlanner.plan(source, snapshot, mapOf("monitored-battery" to "30")) }
        fails("不支持") { ChargingPlanner.plan(source, snapshot, mapOf("qcom,dc-icl-ua" to "2000")) }
        val malformed = source.replace("<3000000>;", "<0 3000000>;")
        assertNotNull(charger(malformed).fields.first { it.parameter.name == "qcom,fcc-max-ua" }.issue)
        fails("32 位") { ChargingPlanner.plan(malformed, charger(malformed), mapOf("qcom,fcc-max-ua" to "2800")) }
    }

    @Test fun rejectsEmptyNoOpAndInvalidPrecisionRangeOrBoolean() {
        fails { ChargingPlanner.plan(source, charger(), emptyMap()) }
        fails("没有变化") { ChargingPlanner.plan(source, charger(), mapOf("qcom,fcc-max-ua" to "3000.000")) }
        for (value in listOf("", "-1", "0", "1e3", "0x100", "NaN", "1,500", "1.0001", "4294967.296", "999999999999999999999999999")) {
            fails { ChargingPlanner.plan(source, charger(), mapOf("qcom,fcc-max-ua" to value)) }
        }
        fails { ChargingPlanner.plan(source, charger(), mapOf("qcom,hvdcp-disable" to "1")) }
        fails { ChargingPlanner.plan(source, charger(), mapOf("qcom,auto-recharge-soc" to "101")) }
        assertEquals(0L, ChargingPlanner.preview(charger(), mapOf("qcom,auto-recharge-soc" to "0")).values.values.single())
    }

    @Test fun validatesRelatedFieldsTogetherWithoutConfusingInputCurrentAndBatteryCurrent() {
        val battery = nodes().first { it.nodePath == "/battery" }
        fails("终止电流") { ChargingPlanner.plan(source, battery, mapOf("constant-charge-current-max-microamp" to "50", "precharge-current-microamp" to "25")) }
        fails("预充电电流") { ChargingPlanner.plan(source, battery, mapOf("precharge-current-microamp" to "2500")) }
        fails("过压") { ChargingPlanner.plan(source, battery, mapOf("constant-charge-voltage-max-microvolt" to "4600")) }
        fails("重新充电电压") { ChargingPlanner.plan(source, charger(), mapOf("qcom,fv-max-uv" to "4100")) }
        assertEquals(2, ChargingPlanner.plan(source, charger(), mapOf("qcom,fv-max-uv" to "4100", "qcom,auto-recharge-vbat-mv" to "4000")).transaction.operationCount)
        assertEquals(1, ChargingPlanner.plan(source, charger(), mapOf("qcom,usb-icl-ua" to "1000")).transaction.operationCount)
    }

    @Test fun detectsAnonymousOverlayNodesAndKeepsEntriesDistinct() {
        val text = """
            /dts-v1/;
            / {
                fragment@0 {
                    __overlay__ {
                        qcom,fcc-max-ua = <2000000>;
                    };
                };
            };
        """.trimIndent()
        val result = ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(0, text), DeviceTreeParser.parse(1, text)))
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.key }.distinct().size)
        assertTrue(result.all { it.nodePath == "/fragment@0/__overlay__" })
    }

    @Test fun neverTreatsOverlayFixupsAsChargingParameters() {
        val text = """
            /dts-v1/;
            / {
                __symbols__ {
                    battery = "/battery";
                };
                __fixups__ {
                    battery = "/fragment@0:target:0";
                };
                __local_fixups__ {
                    charger {
                        qcom,fcc-max-ua = <0>;
                    };
                };
                charger {
                    reg = <0x123>;
                    child {
                        reg = <2>;
                    };
                };
            };
        """.trimIndent()
        val result = nodes(text)
        assertEquals(listOf("/charger"), result.map { it.nodePath })
        assertEquals(0, result.single().editableCount)
    }

    @Test fun rejectsDuplicateAndNonBooleanFlagsAndOnlySynthesizesVerifiedFlags() {
        val duplicate = source.replace("qcom,fcc-max-ua = <3000000>;", "qcom,fcc-max-ua = <3000000>;\n    qcom,fcc-max-ua = <2000000>;")
        assertNotNull(charger(duplicate).fields.first { it.parameter.name == "qcom,fcc-max-ua" }.issue)
        val flag = source.replace("qcom,hvdcp-disable;", "qcom,hvdcp-disable = <1>;")
        fails { ChargingPlanner.plan(flag, charger(flag), mapOf("qcom,hvdcp-disable" to "false")) }
        val unknown = source.replace("qcom,qpnp-smb5", "vendor,charger")
        assertFalse(charger(unknown).fields.any { it.parameter.name == "qcom,usb-pd-disable" })
    }

    @Test fun decimalDisplayRoundTripsAllU32Bits() {
        val parameter = ChargingAnalyzer.parameters.first { it.name == "qcom,fcc-max-ua" }
        for (value in listOf(1L, 999L, 1000L, 1234567L, 0xffffffffL)) {
            val field = ChargingField(parameter, true, "<$value>", value)
            assertEquals(value, ChargingAnalyzer.parseInput(parameter, ChargingAnalyzer.displayValue(field)))
        }
    }

    @Test fun strictScalarDecoderRejectsArraysReferencesAndWrongWidths() {
        for (raw in listOf("<0 100>", "<1 2 3>", "<&battery>", "[00 00 00 00 00 00 00 01]", "[01]", "\"abcdefg\"", "\"a\"")) {
            assertNull(raw, DtsNumericValueCodec.decodeU32(raw))
        }
        assertEquals(0xffffffffL, DtsNumericValueCodec.decodeU32("<0xffffffff>"))
        assertEquals(0x61626300L, DtsNumericValueCodec.decodeU32("\"abc\""))
        assertEquals(3000000L, DtsNumericValueCodec.decodeU32("[00 2d c6 c0]"))
    }

    @Test fun editsPrintableStringWithoutChangingBinaryWidth() {
        val text = source.replace("<3000000>;", "\"abc\";")
        val plan = ChargingPlanner.plan(text, charger(text), mapOf("qcom,fcc-max-ua" to "2000"))
        assertEquals("[00 1e 84 80]", charger(plan.replayedText).fields.first { it.parameter.name == "qcom,fcc-max-ua" }.rawValue)
    }

    @Test fun unmodifiedVendorSentinelsDoNotBlockOtherEdits() {
        val text = source.replace("<95>;", "<0xffffffff>;")
        val node = charger(text)
        val inputs = node.fields.filter { it.issue == null }.associate { it.parameter.name to ChargingAnalyzer.displayValue(it) } + ("qcom,fcc-max-ua" to "2800")
        assertEquals(1, ChargingPlanner.plan(text, node, inputs).transaction.operationCount)
    }

    @Test fun chargeInhibitOnlyAcceptsDocumentedValues() {
        val parameter = ChargingAnalyzer.parameters.first { it.name == "qcom,chg-inhibit-threshold-mv" }
        for (value in listOf(50L, 100L, 200L, 300L)) assertEquals(value, ChargingAnalyzer.parseInput(parameter, value.toString()))
        fails { ChargingAnalyzer.parseInput(parameter, "150") }
    }
}
