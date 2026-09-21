package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeValueCodecTest
{
    @Test
    fun encodesStringAndStringList()
    {
        assertEquals("\"hello\"", DeviceTreeValueCodec.encode(PropertyType.STRING, "hello"))
        assertEquals(
            "\"60\", \"120\"",
            DeviceTreeValueCodec.encode(PropertyType.STRING_LIST, "60\n120")
        )
    }

    @Test
    fun encodesUnsignedCellInDecimalAndHex()
    {
        assertEquals(
            "<144>",
            DeviceTreeValueCodec.encode(PropertyType.U32, "144", NumberBase.DECIMAL)
        )
        assertEquals(
            "<0x90>",
            DeviceTreeValueCodec.encode(PropertyType.U32, "144", NumberBase.HEX)
        )
    }

    @Test
    fun roundTripsByteArrayAndCells()
    {
        assertEquals(
            "[01 ff a0]",
            DeviceTreeValueCodec.encode(PropertyType.BYTE_ARRAY, "01 ff a0")
        )
        assertEquals(
            "<0x1 2 0xff>",
            DeviceTreeValueCodec.encode(PropertyType.CELLS, "0x1 2 0xff")
        )
    }

    @Test
    fun booleanHasNoRawValue()
    {
        assertNull(DeviceTreeValueCodec.encode(PropertyType.BOOLEAN, ""))
    }

    @Test
    fun validatesU32Range()
    {
        assertTrue(DeviceTreeValueCodec.validate(PropertyType.U32, "4294967296") != null)
        assertNull(DeviceTreeValueCodec.validate(PropertyType.U32, "0xffffffff"))
    }

    @Test
    fun parserDoesNotAssumeTwoCellsAreU64()
    {
        val dts = """
            / {
                pair = <0x1 0x2>;
            };
        """.trimIndent()

        val property = DeviceTreeParser.parse(0, dts).root.properties.single()
        assertEquals(PropertyType.CELLS, property.type)
    }
}
