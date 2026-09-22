package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.*
import org.junit.Test

class DeviceTreeEditValidatorTest {
    private fun source(extra: String = "", consumer: String = "") = """
        / {
            target: target {
                phandle = <0x42>;
                child {
                    enabled;
                };
            };
            consumer {
                $consumer
            };
            $extra
        };
    """.trimIndent()

    private fun check(text: String, deleting: Boolean) {
        val change = if (deleting) DeviceTreeEditor.buildDeleteNodeChange(0, text, "/target")
            else DeviceTreeEditor.buildRenameNodeChange(0, text, "/target", "renamed")
        DeviceTreeEditValidator.validate(DeviceTreeParser.parse(0, text), change)
    }

    @Test
    fun deletionRejectsSymbolicAndNumericConsumers() {
        listOf("link = <&target>;", "link = <0x42 1>;").forEach { consumer ->
            val failure = assertThrows(IllegalArgumentException::class.java) { check(source(consumer = consumer), true) }
            assertTrue(failure.message!!.contains("/consumer/link"))
        }
    }

    @Test
    fun renameRejectsPathReferencesToDescendants() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            check(source(consumer = "link = <&{/target/child}>;"), false)
        }
        assertTrue(failure.message!!.contains("/consumer/link"))
    }

    @Test
    fun renameKeepsValidLabelAndNumericReferences() {
        check(source(consumer = "link = <&target>;"), false)
        check(source(consumer = "link = <0x42>;"), false)
    }

    @Test
    fun metadataAndAliasPathsBlockStructuralEdits() {
        listOf("__symbols__", "__fixups__", "aliases", "chosen").forEach { name ->
            val suffix = if (name == "__fixups__") ":link:0" else ""
            val text = source(extra = "$name {\n    ref = \"/target/child$suffix\";\n};")
            listOf(true, false).forEach { deleting ->
                val failure = assertThrows(IllegalArgumentException::class.java) { check(text, deleting) }
                assertTrue(failure.message!!.contains("/$name/ref"))
            }
        }
    }

    @Test
    fun localFixupMirrorsBlockRenameEvenWithoutAnIncomingReference() {
        val text = source(extra = "__local_fixups__ {\n    target {\n        link = <0>;\n    };\n};")
        assertThrows(IllegalArgumentException::class.java) { check(text, false) }
    }

    @Test
    fun unreferencedSubtreesAndInternalReferencesCanBeDeleted() {
        check(source(), true)
        check(source().replace("phandle = <0x42>;", "phandle = <0x42>;\n            self = <&target>;"), true)
        check(source(consumer = "link = \"/target-other\";"), true)
    }
}
