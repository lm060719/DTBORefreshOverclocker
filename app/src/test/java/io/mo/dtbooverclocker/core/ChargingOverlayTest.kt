package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class ChargingOverlayTest {
    @get:Rule val temp = TemporaryFolder()

    // Reduced from the supplied dtbo_a.img; no binary firmware is checked into the repository.
    private val source = """
        /dts-v1/;
        / {
            fragment@22 {
                target = <0xffffffff>;
                __overlay__ {
                    qcom,wireless-fw-name = "idt9415.bin";
                    qcom,thermal-mitigation = <0x2dc6c0 0x16e360 0xf4240 0x7a120>;
                    #cooling-cells = <2>;
                };
            };
            fragment@41 {
                target = <0xffffffff>;
                __overlay__ {
                    qcom,shutdown-voltage = <3150>;
                    mi,support-shutdown-delay;
                };
            };
            __fixups__ {
                battery_charger = "/fragment@22:target:0", "/fragment@41:target:0";
            };
        };
    """.trimIndent()

    private fun nodes(text: String = source) = ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(0, text)))
    private fun editable(text: String = source) = nodes(text).single { it.editableCount > 0 }
    private fun props(text: String) = DeviceTreeParser.parse(0, text).flatten().flatMap { node ->
        node.properties.map { "${node.path}/${it.name}" to it.rawValue }
    }.toMap()

    @Test fun resolvesExternalTargetAndExposesAllFourLevels() {
        val node = editable()
        assertEquals("/fragment@22/__overlay__", node.nodePath)
        assertEquals("battery_charger", node.targetLabel)
        assertEquals(listOf("3000", "1500", "1000", "500"), node.fields.map(ChargingAnalyzer::displayValue))
        assertEquals(listOf(0, 1, 2, 3), node.fields.map { it.cellIndex })
        assertEquals(4, node.fields.map { it.inputKey }.distinct().size)
        assertEquals(2, nodes().size)
        assertEquals("battery_charger", nodes().last().targetLabel)
        assertEquals(0, nodes().last().editableCount)
    }

    @Test fun handlesDtcEmbeddedNulStringLists() {
        for (separator in listOf("\\0", "\\000", "\\x00")) {
            val nulList = source.replace("\", \"/fragment@41", "$separator/fragment@41")
            assertEquals(4, editable(nulList).editableCount)
            assertEquals(2, nodes(nulList).count { it.targetLabel == "battery_charger" })
        }
    }

    @Test fun editsWholeTableAsOneUndoableOperationAndPreservesFixups() {
        val node = editable()
        val inputs = node.fields.associate { it.inputKey to ChargingAnalyzer.displayValue(it) } + mapOf(
            "qcom,thermal-mitigation[0]" to "2800.123", "qcom,thermal-mitigation[1]" to "1400"
        )
        val plan = ChargingPlanner.plan(source, node, inputs)
        assertEquals(1, plan.transaction.operationCount)
        assertEquals(2, plan.transaction.moduleChange?.changes?.size)
        assertEquals(listOf(2800123L, 1400000L, 1000000L, 500000L), editable(plan.replayedText).fields.map { it.value })
        val allowed = "/fragment@22/__overlay__/qcom,thermal-mitigation"
        assertEquals(props(source).filterKeys { it != allowed }, props(plan.replayedText).filterKeys { it != allowed })
        val restored = DeviceTreeEditor.apply(plan.replayedText, plan.transaction.operations.single().inverse())
        assertEquals(props(source), props(restored))
    }

    @Test fun rejectsAscendingNegativeOverflowAndInventedLevels() {
        for (inputs in listOf(
            mapOf("qcom,thermal-mitigation[1]" to "3500"),
            mapOf("qcom,thermal-mitigation[0]" to "1000"),
            mapOf("qcom,thermal-mitigation[0]" to "-1"),
            mapOf("qcom,thermal-mitigation[0]" to "4294967.296"),
            mapOf("qcom,thermal-mitigation[4]" to "0"),
            mapOf("qcom,thermal-mitigation" to "3000 1500")
        )) assertThrows(IllegalArgumentException::class.java) { ChargingPlanner.plan(source, editable(), inputs) }
        val plan = ChargingPlanner.plan(source, editable(), mapOf("qcom,thermal-mitigation[3]" to "0"))
        assertEquals(0L, editable(plan.replayedText).fields.last().value)
    }

    @Test fun rejectsChangedExternalTargetAndDoesNotGuessDriverFromValueMagnitude() {
        assertThrows(IllegalArgumentException::class.java) {
            ChargingPlanner.plan(source.replace("battery_charger =", "other_charger ="), editable(), mapOf("qcom,thermal-mitigation[0]" to "2800"))
        }
        assertTrue(nodes(source.replace("battery_charger =", "other_charger =")).all { it.editableCount == 0 })
        assertTrue(nodes(source.replace("qcom,wireless-fw-name", "vendor,wireless-fw-name")).all { it.editableCount == 0 })
        assertTrue(nodes(source.replace(":target:0", ":target:4")).all { it.editableCount == 0 })
        assertTrue(nodes(source.replace("<0xffffffff>", "<42>")).all { it.editableCount == 0 })
        val mismatched = source.replace("qcom,wireless-fw-name =", "compatible = \"qcom,qpnp-smb5\";\n            qcom,wireless-fw-name =")
        assertTrue(nodes(mismatched).flatMap { it.fields }.filter { it.parameter.name == "qcom,thermal-mitigation" }.all { it.issue != null })
    }

    @Test fun knownGlinkCompatibleDoesNotRequireExternalFixups() {
        val direct = source.replace("qcom,wireless-fw-name =", "compatible = \"qcom,battery-charger\";\n            qcom,wireless-fw-name =")
            .replace("battery_charger =", "unrelated =")
        assertEquals(4, editable(direct).editableCount)
    }

    @Test fun actualReferenceImageExposesEditableTableAndPreservesAllOtherProperties() {
        val path = System.getProperty("dtbo.chargingSampleImage", "").orEmpty()
        assumeTrue("Run with -PchargingSampleImage=<dtbo_a.img>", path.isNotBlank())
        val image = DtboImageCodec.parse(File(path))
        val root = temp.newFolder()
        val files = image.entries.mapIndexed { index, entry -> File(root, "entry_$index.dts").apply { writeText(render(entry.decodedBytes)) } }
        val workspace = DtboWorkspace(root, File(path), File(root, "metadata"), image.metadata, image, emptyList(), files, emptyList())
        val report = CapabilityScanner.scan(workspace)
        assertEquals(CapabilityStatus.AVAILABLE, report.finding(CapabilityKind.CHARGING)?.status)
        val node = report.chargingNodes.single { it.editableCount > 0 }
        assertEquals("/fragment@22/__overlay__", node.nodePath)
        assertEquals(listOf(3000000L, 1500000L, 1000000L, 500000L), node.fields.map { it.value })
        val text = files[node.entryIndex].readText()
        val plan = ChargingPlanner.plan(text, node, mapOf("qcom,thermal-mitigation[0]" to "2800"))
        assertEquals(1, plan.transaction.operationCount)
        val modified = plan.transaction.operations.single().allowedPropertyPath()
        assertEquals(props(text).filterKeys { it != modified }, props(plan.replayedText).filterKeys { it != modified })
        assertEquals(props(text), props(DeviceTreeEditor.apply(plan.replayedText, plan.transaction.operations.single().inverse())))
        println("Reference image: ${node.key}, ${node.editableCount} editable levels; ${props(text).size} properties checked")
    }

    /** Test-only lossless value rendering, using the production FDT reader on the supplied image. */
    private fun render(bytes: ByteArray): String {
        class Node(val name: String) {
            val properties = linkedMapOf<String, ByteArray>()
            val children = linkedMapOf<String, Node>()
        }
        val root = Node("/")
        FdtReader.readAllProperties(bytes).forEach { (path, value) ->
            val segments = path.split('/').filter(String::isNotEmpty)
            var node = root
            segments.dropLast(1).forEach { name -> node = node.children.getOrPut(name) { Node(name) } }
            node.properties[segments.last()] = value
        }
        return buildString {
            appendLine("/dts-v1/;")
            fun emit(node: Node, indent: String) {
                appendLine("$indent${node.name} {")
                node.properties.forEach { (name, bytes) ->
                    val raw = when {
                        bytes.isEmpty() -> ""
                        bytes.last() == 0.toByte() && bytes.all { it == 0.toByte() || (it.toInt() and 255) in 32..126 } ->
                            " = " + bytes.toString(Charsets.US_ASCII).dropLast(1).split('\u0000').joinToString(", ") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
                        bytes.size % 4 == 0 -> {
                            val buffer = ByteBuffer.wrap(bytes)
                            " = <" + List(bytes.size / 4) { "0x${buffer.int.toUInt().toString(16)}" }.joinToString(" ") + ">"
                        }
                        else -> " = [" + bytes.joinToString(" ") { "%02x".format(it) } + "]"
                    }
                    appendLine("$indent    $name$raw;")
                }
                node.children.values.forEach { emit(it, "$indent    ") }
                appendLine("$indent};")
            }
            emit(root, "")
        }
    }
}
