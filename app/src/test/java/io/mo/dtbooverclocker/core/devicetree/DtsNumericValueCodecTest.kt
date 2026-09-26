package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Test

class DtsNumericValueCodecTest
{
    @Test
    fun decodesCellsStringAndByteArrayRepresentations()
    {
        assertEquals(144L, DtsNumericValueCodec.decode("<0x90>"))
        assertEquals(144L, DtsNumericValueCodec.decode("<144>"))
        assertEquals(1_632_000_000L, DtsNumericValueCodec.decode("\"aFX\""))
        assertEquals(1_632_000_000L, DtsNumericValueCodec.decode("[61 46 58 00]"))
        assertEquals(800_000_000L, DtsNumericValueCodec.decode("<0x0 0x2faf0800>"))
    }

    @Test
    fun encodeLikePreservesCellWidthAndNumericStyle()
    {
        assertEquals("<0x90>", DtsNumericValueCodec.encodeLike("<0x78>", 144))
        assertEquals("<144>", DtsNumericValueCodec.encodeLike("<120>", 144))
        assertEquals(
            "<0x0 0x39387000>",
            DtsNumericValueCodec.encodeLike("<0x0 0x2faf0800>", 960_000_000L)
        )
    }

    @Test
    fun decodesAndReencodesMultiCellListsForCoupledDisplayProperties()
    {
        val raw = "<0x5a0 0x14 0x5a0 0x14 0x5a0 0x14>"
        assertEquals(
            listOf(1440L, 20L, 1440L, 20L, 1440L, 20L),
            DtsNumericValueCodec.decodeCells(raw)
        )
        assertEquals(
            "<0x438 0x14 0x438 0x14 0x438 0x14>",
            DtsNumericValueCodec.encodeCellsLike(
                raw,
                listOf(1080L, 20L, 1080L, 20L, 1080L, 20L)
            )
        )
    }

    @Test
    fun stringAndByteRepresentationsAreSafelyReencodedAsEquivalentBytes()
    {
        assertEquals(
            "[72 70 e0 00]",
            DtsNumericValueCodec.encodeLike("\"aFX\"", 1_920_000_000L)
        )
        assertEquals(
            "[72 70 e0 00]",
            DtsNumericValueCodec.encodeLike("[61 46 58 00]", 1_920_000_000L)
        )
        assertEquals(1_920_000_000L, DtsNumericValueCodec.decode("[72 70 e0 00]"))
    }
}
