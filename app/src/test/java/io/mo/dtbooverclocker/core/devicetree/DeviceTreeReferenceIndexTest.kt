package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeReferenceIndexTest
{
    @Test
    fun resolvesDirectLabelAndPathReferences()
    {
        val dts = """
            /dts-v1/;
            / {
                panel: panel@0 {
                    phandle = <0x10>;
                };

                consumer@0 {
                    panel-ref = <&panel>;
                    path-ref = <&{/panel@0}>;
                };
            };
        """.trimIndent()

        val document = DeviceTreeParser.parse(0, dts)
        val index = DeviceTreeReferenceIndexer.build(document)

        assertEquals("/panel@0", index.labels["panel"])
        assertEquals("/panel@0", index.phandles[0x10])

        val outgoing = index.outgoing("/consumer@0")
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.all { it.resolved })
        assertTrue(outgoing.any { it.kind == DeviceTreeReferenceKind.LABEL })
        assertTrue(outgoing.any { it.kind == DeviceTreeReferenceKind.PATH })
    }

    @Test
    fun resolvesSymbolsNodeLabels()
    {
        val dts = """
            /dts-v1/;
            / {
                fragment@0 {
                    __overlay__ {
                        panel@0 {
                            phandle = <0x20>;
                        };
                    };
                };

                __symbols__ {
                    display_panel = "/fragment@0/__overlay__/panel@0";
                };

                consumer@0 {
                    panel-ref = <&display_panel>;
                };
            };
        """.trimIndent()

        val document = DeviceTreeParser.parse(0, dts)
        val index = DeviceTreeReferenceIndexer.build(document)

        assertEquals(
            "/fragment@0/__overlay__/panel@0",
            index.labels["display_panel"]
        )
        assertEquals(
            "/fragment@0/__overlay__/panel@0",
            index.outgoing("/consumer@0").single().targetNodePath
        )
    }

    @Test
    fun resolvesLocalFixupAndIgnoresPlainNumericCollision()
    {
        val dts = """
            /dts-v1/;
            / {
                target@0 {
                    phandle = <0x33>;
                };

                consumer@0 {
                    real-ref = <0x33 0x1>;
                    plain-number = <0x33>;
                };

                __local_fixups__ {
                    consumer@0 {
                        real-ref = <0x0>;
                    };
                };
            };
        """.trimIndent()

        val index = DeviceTreeReferenceIndexer.build(DeviceTreeParser.parse(0, dts))
        val refs = index.outgoing("/consumer@0")

        assertEquals(1, refs.size)
        assertEquals(DeviceTreeReferenceKind.LOCAL_FIXUP, refs.single().kind)
        assertEquals("/target@0", refs.single().targetNodePath)
        assertEquals(0, refs.single().cellOffsetBytes)
    }

    @Test
    fun recordsExternalFixupWithoutTreatingItAsInternalFailure()
    {
        val dts = """
            /dts-v1/;
            / {
                consumer@0 {
                    clocks = <0xffffffff>;
                };

                __fixups__ {
                    gcc = "/consumer@0:clocks:0";
                };
            };
        """.trimIndent()

        val index = DeviceTreeReferenceIndexer.build(DeviceTreeParser.parse(0, dts))
        val ref = index.outgoing("/consumer@0").single()

        assertEquals(DeviceTreeReferenceKind.EXTERNAL_FIXUP, ref.kind)
        assertEquals("&gcc", ref.token)
        assertFalse(ref.resolved)
        assertEquals(1, index.externalFixups().size)
        assertTrue(index.unresolved().isEmpty())
    }

    @Test
    fun keepsUnresolvedLabelReferencesVisible()
    {
        val dts = """
            /dts-v1/;
            / {
                consumer@0 {
                    missing = <&unknown_label>;
                };
            };
        """.trimIndent()

        val index = DeviceTreeReferenceIndexer.build(DeviceTreeParser.parse(0, dts))
        val ref = index.references.single()

        assertFalse(ref.resolved)
        assertEquals("&unknown_label", ref.token)
        assertTrue(index.unresolved().contains(ref))
    }
    @Test
    fun parsesMultipleExternalFixupDescriptorsWithoutStaticQuotedRegex()
    {
        val dts = """
            /dts-v1/;
            / {
                consumer@0 {
                    clocks = <0xffffffff 0xffffffff>;
                };

                __fixups__ {
                    gcc = "/consumer@0:clocks:0", "/consumer@0:clocks:4";
                };
            };
        """.trimIndent()

        val index = DeviceTreeReferenceIndexer.build(DeviceTreeParser.parse(0, dts))
        val refs = index.externalFixups()

        assertEquals(2, refs.size)
        assertEquals(listOf(0, 4), refs.mapNotNull { it.cellOffsetBytes }.sorted())
        assertTrue(refs.all { it.sourceNodePath == "/consumer@0" })
    }

}
