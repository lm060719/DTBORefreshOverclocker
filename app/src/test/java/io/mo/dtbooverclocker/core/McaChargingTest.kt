package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class McaChargingTest {
    private val source = """
        /dts-v1/;
        / {
            buck {
                compatible = "mca,strategy_buckchg";
                in_pd = <1600>;
                chg_pd = <3000>;
                phandle = <99>;
            };
            quick {
                compatible = "mca,quick_charger";
                min_vbat = <3000>;
                max_vbat = <4490>;
                recharge_vbat = <4350>;
                div_max_curr = <5000 12000 15600>;
                batt_para = "0", "atl";
            };
            thermal {
                compatible = "mca_charger_thermal";
                wired_thermal = <1900 1500 1900 3000 13500 13500 13500 13500 13500 13500
                                 1900 1500 1900 3000 11000 11000 11000 11000 11000 11000>;
            };
        };
    """.trimIndent()
    private fun nodes(text: String = source) = ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(0, text)))
    private fun props(text: String) = DeviceTreeParser.parse(0, text).flatten().flatMap { n ->
        n.properties.map { "${n.path}/${it.name}" to it.rawValue }
    }.toMap()

    @Test fun discoversBuckChargerWithoutLegacyKeywordsAndUsesNativeMilliUnits() {
        val buck = nodes().single { it.nodePath == "/buck" }
        assertEquals(2, buck.editableCount)
        assertEquals(listOf("1600", "3000"), buck.fields.map(ChargingAnalyzer::displayValue))
        val plan = ChargingPlanner.plan(source, buck, mapOf("in_pd" to "1550"))
        assertEquals(1550L, nodes(plan.replayedText).first().fields.first().value)
        assertEquals(props(source), props(DeviceTreeEditor.apply(plan.replayedText, plan.transaction.operations.single().inverse())))
    }

    @Test fun editsAscendingRatioArrayWithoutApplyingThermalOrderAndPreservesNeighbors() {
        val node = nodes().single { it.nodePath == "/quick" }
        val plan = ChargingPlanner.plan(source, node, mapOf("div_max_curr[1]" to "11500"))
        val field = nodes(plan.replayedText).single { it.nodePath == "/quick" }.fields.single { it.inputKey == "div_max_curr[1]" }
        assertEquals(11500L, field.value)
        assertEquals(props(source).filterKeys { it != "/quick/div_max_curr" }, props(plan.replayedText).filterKeys { it != "/quick/div_max_curr" })
        assertThrows(IllegalArgumentException::class.java) { ChargingPlanner.preview(node, mapOf("recharge_vbat" to "4500")) }
        assertThrows(IllegalArgumentException::class.java) { ChargingPlanner.preview(node, mapOf("div_max_curr[1]" to "1.5")) }
    }

    @Test fun thermalMatrixValidatesSameColumnAndWritesOneProperty() {
        val node = nodes().single { it.nodePath == "/thermal" }
        assertEquals(20, node.editableCount)
        assertEquals(10, node.fields.map { it.group }.distinct().size)
        val plan = ChargingPlanner.plan(source, node, mapOf("wired_thermal[4]" to "13000", "wired_thermal[14]" to "10500"))
        assertEquals(1, plan.transaction.operationCount)
        assertThrows(IllegalArgumentException::class.java) { ChargingPlanner.preview(node, mapOf("wired_thermal[14]" to "14000")) }
        assertThrows(IllegalArgumentException::class.java) { ChargingPlanner.preview(node, mapOf("wired_thermal[4]" to "10000")) }
        assertEquals(props(source), props(DeviceTreeEditor.apply(plan.replayedText, plan.transaction.operations.single().inverse())))
    }

    @Test fun rejectsUnknownDriverMalformedArrayAndRelocationMetadata() {
        assertTrue(nodes(source.replace("mca,strategy_buckchg", "other,buck")).none { it.nodePath == "/buck" })
        val broken = nodes(source.replace("<5000 12000 15600>", "<5000 12000>"))
            .single { it.nodePath == "/quick" }.fields.single { it.parameter.name == "div_max_curr" }
        assertNotNull(broken.issue)
        assertEquals(0, nodes(source.replace("thermal {", "__local_fixups__ {")).count { it.nodePath == "/__local_fixups__" })
        val duplicate = nodes(source.replace("in_pd = <1600>;", "in_pd = <1600>;\n        in_pd = <1500>;"))
            .single { it.nodePath == "/buck" }.fields.single { it.inputKey == "in_pd" }
        assertNotNull(duplicate.issue)
    }

    @Test fun actualPhoneDtcOutputExposesMcaAndPreservesEveryUneditedProperty() {
        val path = System.getProperty("dtbo.chargingSampleDts").orEmpty()
        assumeTrue("Pass -PchargingSampleDts=<actual DTC output>", path.isNotBlank())
        val fixture = File(path)
        val original = fixture.readText()
        val found = nodes(original)
        assertTrue(found.sumOf { it.editableCount } > 350)
        val thermal = found.single { it.compatible == "\"mca_charger_thermal\"" }
        assertEquals(300, thermal.editableCount)
        val quick = found.single { it.compatible == "\"mca,quick_charger\"" }
        assertTrue(quick.editableCount > 30)
        var changed = ChargingPlanner.plan(original, quick, mapOf("div_max_curr[2]" to "15500")).replayedText
        changed = ChargingPlanner.plan(changed, nodes(changed).single { it.nodePath == thermal.nodePath },
            mapOf("wired_thermal[4]" to "13000", "wireless_thermal[6]" to "8500")).replayedText
        val allowed = setOf("${quick.nodePath}/div_max_curr", "${thermal.nodePath}/wired_thermal", "${thermal.nodePath}/wireless_thermal")
        assertEquals(props(original).filterKeys { it !in allowed }, props(changed).filterKeys { it !in allowed })
        File(fixture.parentFile, "charging-roundtrip.dts").writeText(changed)
        File(fixture.parentFile, "charging-counts.txt").writeText(found.joinToString("\n") { "${it.editableCount}\t${it.nodePath}" })
        val dtc = System.getProperty("dtbo.dtc").orEmpty()
        val imagePath = System.getProperty("dtbo.chargingDeviceImage").orEmpty()
        if (dtc.isNotBlank() && imagePath.isNotBlank()) {
            val compiled = File(fixture.parentFile, "charging-roundtrip.dtb")
            val process = ProcessBuilder(dtc, "-q", "-I", "dts", "-O", "dtb", "-o", compiled.absolutePath,
                File(fixture.parentFile, "charging-roundtrip.dts").absolutePath).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(log, 0, process.waitFor())
            val image = DtboImageCodec.parse(File(imagePath))
            val before = FdtReader.readAllProperties(image.entries[0].decodedBytes)
            val after = FdtReader.readAllProperties(compiled.readBytes())
            assertEquals(before.keys, after.keys)
            assertEquals(allowed, before.keys.filter { !before.getValue(it).contentEquals(after.getValue(it)) }.toSet())
            val rebuilt = File(fixture.parentFile, "charging-roundtrip-verification.img")
            DtboImageCodec.rebuild(image, mapOf(0 to compiled.readBytes()), rebuilt)
            val parsed = DtboImageCodec.parse(rebuilt)
            assertTrue(DtboImageCodec.metadataEquivalent(image.metadata, parsed.metadata))
            assertArrayEquals(compiled.readBytes(), parsed.entries[0].decodedBytes)
            image.entries.drop(1).forEachIndexed { index, entry -> assertArrayEquals(entry.storedBytes, parsed.entries[index + 1].storedBytes) }
            println("Actual phone: ${found.sumOf { it.editableCount }} editable parameters; ${before.size} binary properties checked; DTC compile and DTBO rebuild passed")
        }
    }
}
