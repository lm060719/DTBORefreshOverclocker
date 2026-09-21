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
}
