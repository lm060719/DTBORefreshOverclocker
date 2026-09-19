package io.mo.dtbooverclocker.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class AvbImageEnvelopeTest {
    private val footer = 16320

    private fun fixture(): ByteArray {
        val bytes = ByteArray(24576)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val total = 128
        intArrayOf(0xd7b7ab1e.toInt(), total, 32, 32, 1, 32, 4096, 0,
            64, 64, 0, 0, 0, 0, 0, 0).forEachIndexed { i, n -> b.putInt(i * 4, n) }
        b.putInt(64, 0xd00dfeed.toInt())
        val v = 4096
        b.putInt(v, 0x41564230)
        b.putInt(v + 4, 1)
        b.putLong(v + 20, 256)
        b.putLong(v + 104, 200)
        val d = v + 256
        b.putLong(d, 2)
        b.putLong(d + 8, 184)
        b.putLong(d + 16, total.toLong())
        "sha256".toByteArray().copyInto(bytes, d + 24)
        b.putInt(d + 56, 4)
        b.putInt(d + 60, 32)
        b.putInt(d + 64, 32)
        "dtbo".toByteArray().copyInto(bytes, d + 132)
        val salt = ByteArray(32) { it.toByte() }
        salt.copyInto(bytes, d + 136)
        MessageDigest.getInstance("SHA-256").digest(salt + bytes.copyOf(total)).copyInto(bytes, d + 168)
        b.putInt(footer, 0x41564266)
        b.putInt(footer + 4, 1)
        b.putLong(footer + 12, total.toLong())
        b.putLong(footer + 20, v.toLong())
        b.putLong(footer + 28, 512)
        return bytes
    }

    @Test fun rebuildMovesVbmetaAndUpdatesDigestWithoutMovingEmbeddedFooter() {
        val original = fixture()
        val payload = original.copyOf(6000).apply { fill(0, 128); this[100] = 7 }
        ByteBuffer.wrap(payload).putInt(4, payload.size)
        val result = AvbImageEnvelope.rebuild(original, 128, payload)
        val b = ByteBuffer.wrap(result)
        assertEquals(original.size, result.size)
        assertEquals(6000L, b.getLong(footer + 12))
        assertEquals(8192L, b.getLong(footer + 20))
        assertEquals(0x41564266, b.getInt(footer))
        assertArrayEquals(payload, result.copyOf(payload.size))
        assertTrue(result.copyOfRange(footer + 64, result.size).all { it == 0.toByte() })
        val salt = ByteArray(32) { it.toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(salt + payload)
        assertArrayEquals(expected, result.copyOfRange(8192 + 424, 8192 + 456))
        AvbImageEnvelope.validate(result, payload.size)
    }

    @Test fun codecKeepsEnvelopeAndNoOpIsByteIdentical() {
        val raw = fixture()
        val original = DtboImageCodec.parse(raw)
        val out = File.createTempFile("avb_roundtrip", ".img")
        try {
            DtboImageCodec.rebuild(original, emptyMap(), out)
            assertArrayEquals(raw, out.readBytes())
            val replacement = original.entries.single().decodedBytes.copyOf().apply { this[12] = 42 }
            DtboImageCodec.rebuild(original, mapOf(0 to replacement), out)
            assertEquals(raw.size.toLong(), out.length())
            assertArrayEquals(replacement, DtboImageCodec.parse(out).entries.single().decodedBytes)
            AvbImageEnvelope.validate(out.readBytes(), 128)
        } finally { out.delete() }
    }

    @Test fun paddedImagesKeepLengthAndBareImagesMayGrow() {
        val original = fixture().copyOf(128)
        val payload = original.copyOf(200)
        assertEquals(200, AvbImageEnvelope.rebuild(original, 128, payload).size)
        assertEquals(512, AvbImageEnvelope.rebuild(original.copyOf(512), 128, payload).size)
        assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.rebuild(original.copyOf(192), 128, payload)
        }
    }

    @Test fun refusesSignedStaleAndUnknownTrailers() {
        val raw = fixture()
        val payload = raw.copyOf(128)
        val signed = raw.copyOf().apply { ByteBuffer.wrap(this).putInt(4096 + 28, 1) }
        assertThrows(IllegalArgumentException::class.java) { AvbImageEnvelope.rebuild(signed, 128, payload) }
        val stale = raw.copyOf().apply { this[100] = 1 }
        assertThrows(IllegalArgumentException::class.java) { AvbImageEnvelope.rebuild(stale, 128, payload) }
        val unknown = raw.copyOf(200).apply { this[150] = 1 }
        assertThrows(IllegalArgumentException::class.java) { AvbImageEnvelope.rebuild(unknown, 128, payload) }
    }

    @Test fun refusesOverflowAndOutOfBoundsMetadata() {
        val raw = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.rebuild(raw, 128, ByteArray(footer))
        }
        val invalid = raw.copyOf().apply { ByteBuffer.wrap(this).putLong(footer + 20, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.rebuild(invalid, 128, raw.copyOf(128))
        }
    }
}
