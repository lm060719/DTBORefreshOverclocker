package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.AvbProtectionState
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

    private fun signedFixture(): ByteArray {
        val bytes = fixture()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val vbmeta = 4096
        val authenticationSize = 576
        val auxiliarySize = 256
        val vbmetaSize = 256 + authenticationSize + auxiliarySize

        bytes.fill(0, vbmeta, vbmeta + vbmetaSize)
        b.putInt(vbmeta, 0x41564230)
        b.putInt(vbmeta + 4, 1)
        b.putInt(vbmeta + 8, 0)
        b.putLong(vbmeta + 12, authenticationSize.toLong())
        b.putLong(vbmeta + 20, auxiliarySize.toLong())
        b.putInt(vbmeta + 28, 2)
        b.putLong(vbmeta + 32, 0)
        b.putLong(vbmeta + 40, 32)
        b.putLong(vbmeta + 48, 32)
        b.putLong(vbmeta + 56, 512)
        b.putLong(vbmeta + 96, 0)
        b.putLong(vbmeta + 104, 200)

        val descriptor = vbmeta + 256 + authenticationSize
        b.putLong(descriptor, 2)
        b.putLong(descriptor + 8, 184)
        b.putLong(descriptor + 16, 128)
        "sha256".toByteArray().copyInto(bytes, descriptor + 24)
        b.putInt(descriptor + 56, 4)
        b.putInt(descriptor + 60, 32)
        b.putInt(descriptor + 64, 32)
        "dtbo".toByteArray().copyInto(bytes, descriptor + 132)
        val salt = ByteArray(32) { (it + 3).toByte() }
        salt.copyInto(bytes, descriptor + 136)
        MessageDigest.getInstance("SHA-256")
            .digest(salt + bytes.copyOf(128))
            .copyInto(bytes, descriptor + 168)

        bytes.fill(0x5a.toByte(), vbmeta + 256 + 32, vbmeta + 256 + 32 + 512)
        val auxiliary = bytes.copyOfRange(
            vbmeta + 256 + authenticationSize,
            vbmeta + vbmetaSize
        )
        MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(vbmeta, vbmeta + 256) + auxiliary)
            .copyInto(bytes, vbmeta + 256)

        b.putLong(footer + 28, vbmetaSize.toLong())
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

    private fun refreshDigest(bytes: ByteArray) {
        val b = ByteBuffer.wrap(bytes)
        val salt = bytes.copyOfRange(4096 + 392, 4096 + 424)
        MessageDigest.getInstance("SHA-256").digest(salt + bytes.copyOf(b.getInt(4)))
            .copyInto(bytes, 4096 + 424)
    }

    private fun addProperty(bytes: ByteArray, value: String) {
        val b = ByteBuffer.wrap(bytes)
        val p = 4096 + 456
        b.putLong(4096 + 104, 248) // 200-byte hash descriptor + 48-byte property descriptor.
        b.putLong(p, 0)
        b.putLong(p + 8, 32)
        b.putLong(p + 16, 3)
        b.putLong(p + 24, 4)
        "key\u0000${value}\u0000".toByteArray().copyInto(bytes, p + 32)
    }

    @Test fun magicInsideSaltAndPropertyIsPreservedInsteadOfTreatedAsExtraFooters() {
        val raw = fixture().apply {
            "AVBf".toByteArray().copyInto(this, 4096 + 392)
            addProperty(this, "AVBf")
            refreshDigest(this)
        }
        val logs = mutableListOf<String>()
        val parsed = AvbImageEnvelope.validate(raw, 128)
        assertEquals(3, parsed.magicCandidates)
        assertEquals(1, parsed.validFooters)
        assertEquals(footer, parsed.layout!!.footer)
        val payload = raw.copyOf(6000).apply { fill(0, 128); this[100] = 7 }
        ByteBuffer.wrap(payload).putInt(4, payload.size)
        val result = AvbImageEnvelope.rebuild(raw, 128, payload, logs::add)
        assertEquals(raw.size, result.size)
        // Vbmeta moved to 8192; salt and property must survive byte-for-byte.
        assertArrayEquals(raw.copyOfRange(4488, 4520), result.copyOfRange(8584, 8616))
        assertArrayEquals(raw.copyOfRange(4552, 4600), result.copyOfRange(8648, 8696))
        assertEquals(3, AvbImageEnvelope.validate(result, payload.size).magicCandidates)
        assertTrue(logs.any { it.contains("[REBUILD_ORIGINAL]") && it.contains("magicCandidates=3, validFooters=1") })
        assertTrue(logs.any { it.contains("[REBUILD_OUTPUT]") && it.contains("magicCandidates=3, validFooters=1") })
        val inputLine = logs.first { it.contains("[REBUILD_ORIGINAL]") && it.contains("input_sha256=") }
        val outputLine = logs.first { it.contains("[REBUILD_OUTPUT]") && it.contains("input_sha256=") }
        assertNotEquals(inputLine.substringAfter("input_sha256="), outputLine.substringAfter("input_sha256="))
    }

    @Test fun outputValidationAcceptsNewMagicInPreservedVbmetaData() {
        val raw = fixture().apply { addProperty(this, "test") }
        assertEquals(1, AvbImageEnvelope.validate(raw, 128).magicCandidates)
        val result = AvbImageEnvelope.rebuild(raw, 128, raw.copyOf(128).apply { this[100] = 9 })
        // Simulate a new magic sequence in output metadata, independently of input scanning.
        addProperty(result, "AVBf")
        val logs = mutableListOf<String>()
        val inspected = AvbImageEnvelope.validate(result, 128, logs::add, "FINAL_VALIDATE")
        assertEquals(2, inspected.magicCandidates)
        assertEquals(1, inspected.validFooters)
        assertTrue(logs.any { it.contains("[FINAL_VALIDATE]") && it.contains("structuralValid=false") })
    }

    @Test fun standard18MiBAnd24MiBDumpKeepTheirContainersAndSameLogicalImage() {
        val logicalSize = 18 * 1024 * 1024
        val physicalSize = 24 * 1024 * 1024
        val raw = fixture().copyOf(physicalSize)
        raw.copyInto(raw, logicalSize - 64, footer, footer + 64)
        raw.fill(0, footer, footer + 64)
        val standard = raw.copyOf(logicalSize)
        val inspected = AvbImageEnvelope.validate(raw, 128)
        assertEquals(physicalSize, inspected.containerSize)
        assertEquals(logicalSize, inspected.logicalImageSize)
        val payload = raw.copyOf(128).apply { this[100] = 5 }
        val fullOutput = AvbImageEnvelope.rebuild(raw, 128, payload)
        val logicalOutput = AvbImageEnvelope.rebuild(standard, 128, payload)
        assertEquals(physicalSize, fullOutput.size)
        assertEquals(logicalSize, logicalOutput.size)
        assertArrayEquals(logicalOutput, fullOutput.copyOf(logicalSize))
        assertTrue(fullOutput.copyOfRange(logicalSize, fullOutput.size).all { it == 0.toByte() })
        val out = File.createTempFile("full_dump_noop", ".img")
        try {
            DtboImageCodec.rebuild(DtboImageCodec.parse(raw), emptyMap(), out)
            assertArrayEquals(raw, out.readBytes())
        } finally { out.delete() }
    }

    @Test fun identicalFootersArePreservedAndUpdatedTogetherIncludingWithTrailingPadding() {
        for (padding in listOf(0, 4096)) {
            val original = fixture()
            val second = original.size - 64
            original.copyInto(original, second, footer, footer + 64)
            val raw = original.copyOf(original.size + padding)
            val logs = mutableListOf<String>()
            val inspected = AvbImageEnvelope.validate(raw, 128, logs::add)
            assertEquals(2, inspected.validFooters)
            assertEquals(listOf(footer, second), inspected.footerOffsets)
            assertEquals(second, inspected.layout!!.footer)
            assertEquals(second + 64, inspected.logicalImageSize)

            val payload = raw.copyOf(6000).apply { fill(0, 128); this[100] = 7 }
            ByteBuffer.wrap(payload).putInt(4, payload.size)
            val result = AvbImageEnvelope.rebuild(raw, 128, payload)
            assertEquals(raw.size, result.size)
            for (offset in listOf(footer, second)) {
                assertEquals(6000L, ByteBuffer.wrap(result).getLong(offset + 12))
                assertEquals(8192L, ByteBuffer.wrap(result).getLong(offset + 20))
            }
            assertArrayEquals(result.copyOfRange(footer, footer + 64), result.copyOfRange(second, second + 64))
            assertEquals(2, AvbImageEnvelope.validate(result, payload.size).validFooters)
            assertTrue(result.copyOfRange(second + 64, result.size).all { it == 0.toByte() })
            val out = File.createTempFile("duplicate_footer_noop", ".img")
            try {
                DtboImageCodec.rebuild(DtboImageCodec.parse(raw), emptyMap(), out)
                assertArrayEquals(raw, out.readBytes())
            } finally { out.delete() }
        }
    }

    @Test fun duplicateFootersDoNotPermitOverwritingTheEarlierFooterOrUnknownPadding() {
        val raw = fixture()
        raw.copyInto(raw, raw.size - 64, footer, footer + 64)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.rebuild(raw, 128, ByteArray(footer))
        }.message!!.contains("超出可用容量"))
        for (offset in listOf(256, 5000, footer + 128)) {
            val corrupt = raw.copyOf().apply { this[offset] = 1 }
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                AvbImageEnvelope.validate(corrupt, 128)
            }.message!!.contains("AVB 区域外存在未知数据"))
        }
    }

    @Test fun conflictingFooterFieldsAreStillRejected() {
        val mutations: List<(ByteBuffer, Int) -> Unit> = listOf(
            { b, offset -> b.putInt(offset + 4, 2) },
            { b, offset -> b.putInt(offset + 8, 1) },
            { b, offset -> b.putLong(offset + 12, 256) }
        )
        mutations.forEach { mutate ->
            val raw = fixture()
            val second = raw.size - 64
            raw.copyInto(raw, second, footer, footer + 64)
            mutate(ByteBuffer.wrap(raw), second)
            val error = assertThrows(IllegalArgumentException::class.java) { AvbImageEnvelope.validate(raw, 128) }
            assertTrue(error.message!!.contains("多个有效 AVB footer"))
            assertTrue(error.message!!.contains("validFooters=2"))
        }
    }

    /** Opt in with -PavbSampleImage=<path to the reported dtbo_a.img>. Never modifies the sample. */
    @Test fun reportedImageImportsAndRebuildsWithBothFootersIntact() {
        val sample = System.getProperty("dtbo.avbSampleImage", "") ?: ""
        org.junit.Assume.assumeTrue("Local AVB sample was not configured", sample.isNotBlank())
        val raw = File(sample).readBytes()
        assertEquals("26aeb45e60c4b24ebdfa50173c96164583289812c1cb5dd032cabe2f1db8a625",
            io.mo.dtbooverclocker.util.HashUtils.sha256(raw))
        val original = DtboImageCodec.parse(raw)
        val inspection = AvbImageEnvelope.validate(raw, original.metadata.totalSize)
        assertEquals(2, inspection.validFooters)
        assertEquals(raw.size, inspection.logicalImageSize)
        val out = File.createTempFile("reported_avb_roundtrip", ".img")
        try {
            DtboImageCodec.rebuild(original, emptyMap(), out)
            assertArrayEquals(raw, out.readBytes())
            // Change the FDT boot CPU ID to exercise the rebuild path without altering the tree.
            val replacement = original.entries.first().decodedBytes.copyOf().apply {
                this[31] = (this[31].toInt() xor 1).toByte()
            }
            DtboImageCodec.rebuild(original, mapOf(0 to replacement), out)
            val result = out.readBytes()
            val rebuilt = DtboImageCodec.parse(result)
            assertEquals(raw.size, result.size)
            assertArrayEquals(replacement, rebuilt.entries.first().decodedBytes)
            assertTrue(DtboImageCodec.metadataEquivalent(original.metadata, rebuilt.metadata))
            assertEquals(2, AvbImageEnvelope.validate(result, rebuilt.metadata.totalSize).validFooters)
            assertArrayEquals(result.copyOfRange(20971456, 20971520), result.copyOfRange(25165760, 25165824))
            assertFalse(raw.contentEquals(result))
            println("PASS: reported 24 MiB image imports, no-op is byte-identical, changed payload passes AVB with both footers preserved")
        } finally { out.delete() }
    }

    @Test fun rejectsTwoStructuralFootersEvenIfSecondUsesUnsupportedSigning() {
        val raw = fixture()
        raw.copyInto(raw, 8192, 4096, 4608)
        ByteBuffer.wrap(raw).putInt(8192 + 28, 1)
        val second = raw.size - 64
        raw.copyInto(raw, second, footer, footer + 64)
        ByteBuffer.wrap(raw).putLong(second + 20, 8192)
        val error = assertThrows(IllegalArgumentException::class.java) { AvbImageEnvelope.validate(raw, 128) }
        assertTrue(error.message!!.contains("多个有效 AVB footer"))
        assertTrue(error.message!!.contains("validFooters=2"))
        assertTrue(error.message!!.contains("candidateOffsets=[$footer, $second]"))
    }

    @Test fun structuralRecognitionDoesNotSilentlyDropUnsupportedLayouts() {
        val signed = fixture().apply { ByteBuffer.wrap(this).putInt(4096 + 28, 1) }
        assertEquals(1, AvbImageEnvelope.inspect(signed, 128).validFooters)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(signed, 128)
        }.message!!.contains("AVB 签名"))

        val unknown = fixture().apply { ByteBuffer.wrap(this).putLong(4096 + 256, 5) }
        assertEquals(1, AvbImageEnvelope.inspect(unknown, 128).validFooters)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(unknown, 128)
        }.message!!.contains("descriptor 类型 5"))

        val future = fixture().apply { ByteBuffer.wrap(this).putInt(footer + 8, 1) }
        assertEquals(1, AvbImageEnvelope.inspect(future, 128).validFooters)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(future, 128)
        }.message!!.contains("不支持的 AVB footer 版本"))
    }

    @Test fun signedAvbMayEnterAnalysisWorkspaceButRebuildRemainsBlocked() {
        val raw = signedFixture()
        val logs = mutableListOf<String>()
        val inspection = AvbImageEnvelope.validateForAnalysis(raw, 128, logs::add, "STAGED_INPUT")

        assertEquals(AvbProtectionState.SIGNED, inspection.protectionState)
        assertEquals("SHA256_RSA4096", inspection.algorithm)
        assertEquals(1, inspection.validFooters)
        assertTrue(logs.any { it.contains("允许进入设备树工作区") })

        val strictError = assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(raw, 128)
        }
        assertTrue(strictError.message!!.contains("AVB 签名"))

        val rebuildError = assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.rebuild(raw, 128, raw.copyOf(128).apply { this[100] = 7 })
        }
        assertTrue(rebuildError.message!!.contains("AVB 签名"))
    }

    /** Opt in with -Ddtbo.signedSampleImage=<path to the signed OPlus/OnePlus dtbo_a.img>. */
    @Test fun reportedSignedOplusImageAnalyzesWithoutWeakeningRebuildSafety() {
        val sample = System.getProperty("dtbo.signedSampleImage", "") ?: ""
        org.junit.Assume.assumeTrue("Local signed OPlus AVB sample was not configured", sample.isNotBlank())
        val raw = File(sample).readBytes()
        assertEquals(
            "338afe90eee2e24448303a851ed7d67a435c44848f9aaaff6ed4a2ffa90c4684",
            io.mo.dtbooverclocker.util.HashUtils.sha256(raw)
        )
        val original = DtboImageCodec.parse(raw)
        val inspection = AvbImageEnvelope.validateForAnalysis(raw, original.metadata.totalSize)
        assertEquals(AvbProtectionState.SIGNED, inspection.protectionState)
        assertEquals("SHA256_RSA4096", inspection.algorithm)
        assertEquals(25165760, inspection.layout?.footer)
        assertEquals(raw.size, inspection.logicalImageSize)

        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(raw, original.metadata.totalSize)
        }.message!!.contains("AVB 签名"))
    }
    @Test fun invalidMagicInPaddingIsNotPermissionToDiscardUnknownBytes() {
        for (offset in listOf(256, 5000, footer + 128)) {
            val raw = fixture().apply { "AVBf".toByteArray().copyInto(this, offset) }
            val error = assertThrows(IllegalArgumentException::class.java) {
                AvbImageEnvelope.rebuild(raw, 128, raw.copyOf(128))
            }
            assertTrue(error.message!!.contains("AVB 区域外存在未知数据"))
            assertTrue(error.message!!.contains("magicCandidates=2, validFooters=1"))
        }
    }

    @Test fun truncatedFooterIsDiagnosedIncludingMagicAtVeryEnd() {
        val logs = mutableListOf<String>()
        val raw = fixture().copyOf(132).apply { "AVBf".toByteArray().copyInto(this, 128) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(raw, 128, logs::add, "STAGED_INPUT")
        }
        assertTrue(error.message!!.contains("没有任何候选通过结构验证"))
        assertTrue(error.message!!.contains("magicCandidates=1, validFooters=0"))
        assertTrue(logs.any { it.contains("offset=128") && it.contains("AVB footer 截断") })
    }

    @Test fun rejectsOverflowingVbmetaAndDescriptorRangesWithCandidateReasons() {
        val mutations: List<(ByteBuffer) -> Unit> = listOf(
            { it.putLong(footer + 20, -1) },
            { it.putLong(footer + 28, Long.MAX_VALUE) },
            { it.putLong(4096 + 12, Long.MAX_VALUE) },
            { it.putLong(4096 + 20, Long.MAX_VALUE) },
            { it.putLong(4096 + 96, Long.MAX_VALUE) },
            { it.putLong(4096 + 104, 257) },
            { it.putLong(4096 + 256 + 8, Long.MAX_VALUE) }
        )
        mutations.forEach { mutate ->
            val raw = fixture().apply { mutate(ByteBuffer.wrap(this)) }
            val logs = mutableListOf<String>()
            val error = assertThrows(IllegalArgumentException::class.java) {
                AvbImageEnvelope.validate(raw, 128, logs::add)
            }
            assertTrue(error.message!!.contains("validFooters=0"))
            assertTrue(logs.any { it.contains("structuralValid=false, reason=") })
        }
    }

    @Test fun noAvbAndSizeMismatchHaveDistinctResults() {
        val bare = fixture().copyOf(128)
        assertNull(AvbImageEnvelope.validate(bare, 128).layout)
        assertEquals(128, AvbImageEnvelope.validate(bare, 128).logicalImageSize)
        assertNull(AvbImageEnvelope.validate(bare.copyOf(1024), 128).logicalImageSize)
        val unknown = bare.copyOf(256).apply { this[150] = 1 }
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(unknown, 128)
        }.message!!.contains("未找到 AVBf magic"))
        val mismatch = fixture().apply { ByteBuffer.wrap(this).putLong(footer + 12, 256) }
        assertEquals(1, AvbImageEnvelope.inspect(mismatch, 128).validFooters)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            AvbImageEnvelope.validate(mismatch, 128)
        }.message!!.contains("原始大小与 DTBO 不匹配"))
    }
}
