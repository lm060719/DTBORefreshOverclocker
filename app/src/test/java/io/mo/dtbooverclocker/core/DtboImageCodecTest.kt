package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.DtboBinaryEntry
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboEntryMetadata
import io.mo.dtbooverclocker.model.DtboMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtboImageCodecTest {
    @Test
    fun rebuildRoundTripsAllSupportedCompressionFormats() {
        listOf(0, 1, 2, 3).forEach { compression ->
            val raw = byteArrayOf(0xd0.toByte(), 0x0d, 0xfe.toByte(), 0xed.toByte()) +
                "original-$compression".repeat(16).encodeToByteArray()
            val replacement = byteArrayOf(0xd0.toByte(), 0x0d, 0xfe.toByte(), 0xed.toByte()) +
                "replacement-$compression".repeat(16).encodeToByteArray()

            val metadataEntry = DtboEntryMetadata(
                index = 0,
                storedSize = raw.size,
                storedOffset = 64,
                idHex = "00000012",
                revHex = "00000034",
                flagsHex = "%08x".format(compression),
                customHex = listOf("00000001", "00000002", "00000003"),
                compressionFormat = compression
            )
            val image = DtboBinaryImage(
                metadata = DtboMetadata(
                    magicHex = "d7b7ab1e",
                    totalSize = 64 + raw.size,
                    headerSize = 32,
                    entrySize = 32,
                    entriesOffset = 32,
                    pageSize = 4096,
                    version = 1,
                    entries = listOf(metadataEntry)
                ),
                prefixTemplate = ByteArray(64),
                entries = listOf(DtboBinaryEntry(metadataEntry, raw, raw))
            )

            val out = File.createTempFile("dtbo_codec_$compression", ".img")
            DtboImageCodec.rebuild(image, mapOf(0 to replacement), out)
            val parsed = DtboImageCodec.parse(out)

            assertTrue(DtboImageCodec.metadataEquivalent(image.metadata, parsed.metadata))
            assertArrayEquals(replacement, parsed.entries.single().decodedBytes)
            assertEquals(compression, parsed.entries.single().metadata.compressionFormat)
        }
    }

    @Test
    fun version2PreservesElevenCustomFields() {
        val raw = byteArrayOf(0xd0.toByte(), 0x0d, 0xfe.toByte(), 0xed.toByte(), 1, 2, 3, 4)
        val customs = (1..11).map { "%08x".format(it) }
        val entry = DtboEntryMetadata(
            index = 0,
            storedSize = raw.size,
            storedOffset = 96,
            idHex = "11223344",
            revHex = "55667788",
            flagsHex = "00000000",
            customHex = customs,
            compressionFormat = 0
        )
        val image = DtboBinaryImage(
            metadata = DtboMetadata(
                magicHex = "d7b7ab1e",
                totalSize = 96 + raw.size,
                headerSize = 32,
                entrySize = 64,
                entriesOffset = 32,
                pageSize = 4096,
                version = 2,
                entries = listOf(entry)
            ),
            prefixTemplate = ByteArray(96),
            entries = listOf(DtboBinaryEntry(entry, raw, raw))
        )

        val out = File.createTempFile("dtbo_v2", ".img")
        DtboImageCodec.rebuild(image, emptyMap(), out)
        val parsed = DtboImageCodec.parse(out)

        assertEquals(customs, parsed.metadata.entries.single().customHex)
        assertEquals(64, parsed.metadata.entrySize)
    }
}
