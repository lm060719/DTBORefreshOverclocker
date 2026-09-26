package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeParserTest
{
    private val sample = """
        /dts-v1/;
        / {
            compatible = "demo,board";
            panel: panel@0 {
                rate = <0x78>;
                enabled;
                modes = "60", "120";
            };
        };
    """.trimIndent()

    @Test
    fun parsesHierarchyAndProperties()
    {
        val document = DeviceTreeParser.parse(3, sample)
        assertEquals(listOf("/", "/panel@0"), document.flatten().map { it.path })

        val panel = requireNotNull(document.findNode("/panel@0"))
        assertEquals("panel", panel.label)
        assertEquals(3, panel.propertyCount)
        assertEquals(PropertyType.U32, panel.properties.first { it.name == "rate" }.type)
        assertEquals(PropertyType.BOOLEAN, panel.properties.first { it.name == "enabled" }.type)
        assertEquals(PropertyType.STRING_LIST, panel.properties.first { it.name == "modes" }.type)
    }

    @Test
    fun searchDataKeepsRawStatement()
    {
        val property = DeviceTreeParser.parse(0, sample)
            .findNode("/panel@0")!!
            .properties
            .first { it.name == "rate" }

        assertTrue(property.rawStatement.contains("rate"))
        assertEquals("120", property.displayValue)
    }

    @Test
    fun parsesNodesCarryingSeveralLabels()
    {
        // dtc writes every label of a node on its header, as in OnePlus/Realme DTBOs.
        val document = DeviceTreeParser.parse(0, """
            /dts-v1/;
            / {
                timing_0_37: timing_0_146: timing@0 {
                    rate = <0x78>;
                };
                sibling {
                    ref = <&timing_0_146>;
                };
            };
        """.trimIndent())

        assertEquals(listOf("/", "/timing@0", "/sibling"), document.flatten().map { it.path })
        val timing = requireNotNull(document.findNode("/timing@0"))
        assertEquals("timing_0_37", timing.label)
        assertEquals(listOf("timing_0_37", "timing_0_146"), timing.labels)
        assertEquals("/timing@0", DeviceTreeReferenceIndexer.build(document).labels["timing_0_146"])
    }
}
