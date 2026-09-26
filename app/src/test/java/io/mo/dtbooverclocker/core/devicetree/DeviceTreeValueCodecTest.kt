package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeValueCodecTest
{
    @Test
    fun unsafeStringRepresentationsRemainRaw() {
        listOf("\"\\x41\"", "\"\\101\"", "\"\\0\"", "\"\\a\"", "\"prefix\", <1>").forEach { raw ->
            assertFalse(DeviceTreeValueCodec.supportsTypedEditor(PropertyType.STRING, raw))
            assertEquals(raw, DeviceTreeValueCodec.editableText(PropertyType.STRING, raw))
        }
        listOf("\"\", \"a\"", "\" a \", \"b\"", "\"a\\nb\", \"c\"").forEach { raw ->
            assertFalse(DeviceTreeValueCodec.supportsTypedEditor(PropertyType.STRING_LIST, raw))
            assertEquals(raw, DeviceTreeValueCodec.editableText(PropertyType.STRING_LIST, raw))
        }
    }

    @Test
    fun ordinaryEscapesRoundTripAndLiteralBackslashesStayLiteral() {
        listOf("hello\nworld", "quote: \" and tab:\t", "literal \\x41", "").forEach { value ->
            val raw = DeviceTreeValueCodec.encode(PropertyType.STRING, value)!!
            assertTrue(DeviceTreeValueCodec.supportsTypedEditor(PropertyType.STRING, raw))
            assertEquals(value, DeviceTreeValueCodec.editableText(PropertyType.STRING, raw))
        }
    }

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
