package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.DtboBinaryEntry
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboEntryMetadata
import io.mo.dtbooverclocker.model.DtboMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin/JVM parser + rebuilder for Android DTBO table images.
 *
 * Supported table versions: 0, 1, 2.
 * Supported DT entry compression flag low nibble:
 *   0 = none, 1 = zlib, 2 = gzip, 3 = LZ4 frame.
 *
 * The code deliberately preserves entry id/rev/flags/custom fields and the
 * original metadata prefix while recalculating only fields that must change:
 * total_size, dt_size and dt_offset.
 */
object DtboImageCodec {
    const val DTBO_MAGIC: Long = 0xd7b7ab1eL
    private const val HEADER_SIZE = 32
    private const val ENTRY_SIZE_V0_V1 = 32
    private const val ENTRY_SIZE_V2 = 64
    private const val COMPRESSION_MASK = 0x0fL

    private const val COMP_NONE = 0
    private const val COMP_ZLIB = 1
    private const val COMP_GZIP = 2
    private const val COMP_LZ4 = 3

    fun parse(file: File): DtboBinaryImage {
        require(file.isFile) { "DTBO 文件不存在：${file.absolutePath}" }
        return parse(file.readBytes())
    }

    fun parse(bytes: ByteArray): DtboBinaryImage {
        require(bytes.size >= HEADER_SIZE) { "DTBO 文件小于 32 字节" }

        val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val magic = header.u32()
        val totalSize = header.u32Int("total_size")
        val headerSize = header.u32Int("header_size")
        val entrySize = header.u32Int("dt_entry_size")
        val entryCount = header.u32Int("dt_entry_count")
        val entriesOffset = header.u32Int("dt_entries_offset")
        val pageSize = header.u32Int("page_size")
        val version = header.u32Int("version")

        require(magic == DTBO_MAGIC) {
            "不是 DTBO 镜像：magic=0x${magic.toString(16)}"
        }
        require(headerSize == HEADER_SIZE) { "不支持的 DTBO header_size=$headerSize" }
        val expectedEntrySize = when (version) {
            0, 1 -> ENTRY_SIZE_V0_V1
            2 -> ENTRY_SIZE_V2
            else -> error("不支持的 DTBO version=$version，仅支持 0/1/2")
        }
        require(entrySize == expectedEntrySize) {
            "DTBO version=$version 的 dt_entry_size 应为 $expectedEntrySize，实际为 $entrySize"
        }
        require(entryCount in 1..4096) { "异常的 DTBO entry_count=$entryCount" }
        require(entriesOffset >= headerSize && entriesOffset % 4 == 0) {
            "异常的 dt_entries_offset=$entriesOffset"
        }

        val metadataEndLong = entriesOffset.toLong() + entrySize.toLong() * entryCount.toLong()
        require(metadataEndLong <= Int.MAX_VALUE) { "DTBO 元数据区域过大" }
        val metadataEnd = metadataEndLong.toInt()
        require(metadataEnd <= bytes.size) { "DTBO entry table 越界" }
        require(totalSize in metadataEnd..bytes.size) {
            "DTBO total_size=$totalSize 与实际文件长度 ${bytes.size} 不匹配"
        }

        val entries = ArrayList<DtboBinaryEntry>(entryCount)
        var firstPayloadOffset = totalSize
        repeat(entryCount) { index ->
            val offset = entriesOffset + index * entrySize
            val entryBuffer = ByteBuffer.wrap(bytes, offset, entrySize).order(ByteOrder.BIG_ENDIAN)

            val storedSize = entryBuffer.u32Int("entry[$index].dt_size")
            val storedOffset = entryBuffer.u32Int("entry[$index].dt_offset")
            val imageId = entryBuffer.u32()
            val rev = entryBuffer.u32()

            val flags: Long?
            val customs: List<Long>
            when (version) {
                0 -> {
                    flags = null
                    customs = List(4) { entryBuffer.u32() }
                }

                1 -> {
                    flags = entryBuffer.u32()
                    customs = List(3) { entryBuffer.u32() }
                }

                2 -> {
                    flags = entryBuffer.u32()
                    customs = List(11) { entryBuffer.u32() }
                }

                else -> error("unreachable")
            }

            require(storedSize > 0) { "entry[$index] dt_size=0" }
            val end = storedOffset.toLong() + storedSize.toLong()
            require(storedOffset >= metadataEnd && end <= totalSize.toLong()) {
                "entry[$index] 数据区越界：offset=$storedOffset size=$storedSize total=$totalSize"
            }
            firstPayloadOffset = min(firstPayloadOffset, storedOffset)

            val stored = bytes.copyOfRange(storedOffset, storedOffset + storedSize)
            val compression = ((flags ?: 0L) and COMPRESSION_MASK).toInt()
            val decoded = decompress(stored, compression)
            require(decoded.size >= 4) { "entry[$index] 解压后过小" }

            entries += DtboBinaryEntry(
                metadata = DtboEntryMetadata(
                    index = index,
                    storedSize = storedSize,
                    storedOffset = storedOffset,
                    idHex = hex32(imageId),
                    revHex = hex32(rev),
                    flagsHex = flags?.let(::hex32),
                    customHex = customs.map(::hex32),
                    compressionFormat = compression
                ),
                storedBytes = stored,
                decodedBytes = decoded
            )
        }

        require(firstPayloadOffset >= metadataEnd) { "DTBO payload 与 entry table 重叠" }
        val prefixTemplate = bytes.copyOfRange(0, firstPayloadOffset)

        return DtboBinaryImage(
            metadata = DtboMetadata(
                magicHex = hex32(magic),
                totalSize = totalSize,
                headerSize = headerSize,
                entrySize = entrySize,
                entriesOffset = entriesOffset,
                pageSize = pageSize,
                version = version,
                entries = entries.map { it.metadata }
            ),
            prefixTemplate = prefixTemplate,
            entries = entries,
            originalBytes = bytes
        )
    }

    fun rebuild(
        original: DtboBinaryImage,
        replacementDecodedEntries: Map<Int, ByteArray>,
        output: File
    ) {
        val metadata = original.metadata
        require(metadata.entries.size == original.entries.size) { "DTBO 元数据与条目数量不一致" }
        replacementDecodedEntries.keys.forEach { index ->
            require(index in original.entries.indices) { "替换条目索引越界：$index" }
        }

        if (original.originalBytes != null && replacementDecodedEntries.all { (index, bytes) ->
                bytes.contentEquals(original.entries[index].decodedBytes)
            }) {
            output.parentFile?.mkdirs()
            output.writeBytes(original.originalBytes)
            return
        }

        val payloadStart = original.prefixTemplate.size
        val prefix = original.prefixTemplate.copyOf()
        require(prefix.size >= metadata.entriesOffset + metadata.entrySize * original.entries.size) {
            "原始 DTBO prefixTemplate 不完整"
        }

        data class BuiltEntry(val size: Int, val offset: Int, val stored: ByteArray?)

        val built = ArrayList<BuiltEntry>(original.entries.size)
        val payloadOut = ByteArrayOutputStream()
        val unchangedOffsetMap = mutableMapOf<Pair<Int, Int>, BuiltEntry>()

        original.entries.forEach { entry ->
            val index = entry.metadata.index
            val replacement = replacementDecodedEntries[index]

            if (replacement == null) {
                val identity = entry.metadata.storedOffset to entry.metadata.storedSize
                val reused = unchangedOffsetMap[identity]
                if (reused != null) {
                    built += BuiltEntry(reused.size, reused.offset, null)
                } else {
                    val newOffset = payloadStart + payloadOut.size()
                    payloadOut.write(entry.storedBytes)
                    val value = BuiltEntry(entry.storedBytes.size, newOffset, entry.storedBytes)
                    unchangedOffsetMap[identity] = value
                    built += value
                }
            } else {
                require(replacement.size >= 4) { "replacement entry[$index] 过小" }
                val encoded = compress(replacement, entry.metadata.compressionFormat)
                val newOffset = payloadStart + payloadOut.size()
                payloadOut.write(encoded)
                built += BuiltEntry(encoded.size, newOffset, encoded)
            }
        }

        val payload = payloadOut.toByteArray()
        val totalSizeLong = payloadStart.toLong() + payload.size.toLong()
        require(totalSizeLong <= Int.MAX_VALUE) { "重建 DTBO 超过 2 GiB" }
        val totalSize = totalSizeLong.toInt()

        val prefixBuffer = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
        prefixBuffer.putInt(0, DTBO_MAGIC.toInt())
        prefixBuffer.putInt(4, totalSize)
        prefixBuffer.putInt(8, metadata.headerSize)
        prefixBuffer.putInt(12, metadata.entrySize)
        prefixBuffer.putInt(16, original.entries.size)
        prefixBuffer.putInt(20, metadata.entriesOffset)
        prefixBuffer.putInt(24, metadata.pageSize)
        prefixBuffer.putInt(28, metadata.version)

        original.entries.forEachIndexed { index, entry ->
            val b = built[index]
            val base = metadata.entriesOffset + index * metadata.entrySize
            val eb = ByteBuffer.wrap(prefix, base, metadata.entrySize).order(ByteOrder.BIG_ENDIAN)
            eb.putInt(b.size)
            eb.putInt(b.offset)
            eb.putInt(parseHex32(entry.metadata.idHex).toInt())
            eb.putInt(parseHex32(entry.metadata.revHex).toInt())

            when (metadata.version) {
                0 -> {
                    require(entry.metadata.customHex.size == 4)
                    entry.metadata.customHex.forEach { eb.putInt(parseHex32(it).toInt()) }
                }

                1 -> {
                    eb.putInt(parseHex32(entry.metadata.flagsHex ?: "0").toInt())
                    require(entry.metadata.customHex.size == 3)
                    entry.metadata.customHex.forEach { eb.putInt(parseHex32(it).toInt()) }
                }

                2 -> {
                    eb.putInt(parseHex32(entry.metadata.flagsHex ?: "0").toInt())
                    require(entry.metadata.customHex.size == 11)
                    entry.metadata.customHex.forEach { eb.putInt(parseHex32(it).toInt()) }
                }
            }
        }

        val dtboBytes = prefix + payload
        val imageBytes = original.originalBytes?.let {
            AvbImageEnvelope.rebuild(it, metadata.totalSize, dtboBytes)
        } ?: dtboBytes
        output.parentFile?.mkdirs()
        output.writeBytes(imageBytes)
        require(output.length() == imageBytes.size.toLong()) { "DTBO 重建长度校验失败" }
    }

    fun metadataEquivalent(a: DtboMetadata, b: DtboMetadata): Boolean {
        if (
            !a.magicHex.equals(b.magicHex, ignoreCase = true) ||
            a.headerSize != b.headerSize ||
            a.entrySize != b.entrySize ||
            a.entriesOffset != b.entriesOffset ||
            a.pageSize != b.pageSize ||
            a.version != b.version ||
            a.entries.size != b.entries.size
        ) {
            return false
        }

        return a.entries.zip(b.entries).all { (left, right) ->
            left.index == right.index &&
                left.idHex.equals(right.idHex, ignoreCase = true) &&
                left.revHex.equals(right.revHex, ignoreCase = true) &&
                (left.flagsHex ?: "0").trimHex() == (right.flagsHex ?: "0").trimHex() &&
                left.customHex.map { it.trimHex() } == right.customHex.map { it.trimHex() } &&
                left.compressionFormat == right.compressionFormat
        }
    }

    fun describe(image: DtboBinaryImage): String = buildString {
        val m = image.metadata
        appendLine("dt_table_header:")
        appendLine("               magic = ${m.magicHex}")
        appendLine("          total_size = ${m.totalSize}")
        appendLine("         header_size = ${m.headerSize}")
        appendLine("       dt_entry_size = ${m.entrySize}")
        appendLine("      dt_entry_count = ${m.entries.size}")
        appendLine("     dt_entries_offset = ${m.entriesOffset}")
        appendLine("           page_size = ${m.pageSize}")
        appendLine("             version = ${m.version}")
        m.entries.forEach { e ->
            appendLine("dt_table_entry[${e.index}]:")
            appendLine("             dt_size = ${e.storedSize}")
            appendLine("           dt_offset = ${e.storedOffset}")
            appendLine("                  id = ${e.idHex}")
            appendLine("                 rev = ${e.revHex}")
            e.flagsHex?.let { appendLine("               flags = $it") }
            e.customHex.forEachIndexed { i, value ->
                appendLine("           custom[$i] = $value")
            }
        }
    }

    private fun decompress(stored: ByteArray, format: Int): ByteArray {
        return when (format) {
            COMP_NONE -> stored
            COMP_ZLIB -> InflaterInputStream(ByteArrayInputStream(stored)).use { it.readBytes() }
            COMP_GZIP -> GZIPInputStream(ByteArrayInputStream(stored)).use { it.readBytes() }
            COMP_LZ4 -> Lz4FrameCodec.decompress(stored)
            else -> error("不支持的 DTBO 压缩格式 flags&0x0f=$format")
        }
    }

    private fun compress(decoded: ByteArray, format: Int): ByteArray {
        return when (format) {
            COMP_NONE -> decoded
            COMP_ZLIB -> ByteArrayOutputStream().also { target ->
                DeflaterOutputStream(target, Deflater(Deflater.DEFAULT_COMPRESSION, false)).use { it.write(decoded) }
            }.toByteArray()

            COMP_GZIP -> ByteArrayOutputStream().also { target ->
                GZIPOutputStream(target).use { it.write(decoded) }
            }.toByteArray()

            COMP_LZ4 -> Lz4FrameCodec.compressAsUncompressedFrame(decoded)
            else -> error("不支持的 DTBO 压缩格式 flags&0x0f=$format")
        }
    }

    private fun ByteBuffer.u32(): Long = int.toLong() and 0xffffffffL

    private fun ByteBuffer.u32Int(name: String): Int {
        val value = u32()
        require(value <= Int.MAX_VALUE) { "$name 超出 Android 可处理范围：$value" }
        return value.toInt()
    }

    private fun hex32(value: Long): String = "%08x".format(value and 0xffffffffL)

    private fun parseHex32(value: String): Long {
        val clean = value.trim().removePrefix("0x").removePrefix("0X")
        val result = clean.toLong(16)
        require(result in 0..0xffffffffL) { "32-bit 十六进制值越界：$value" }
        return result
    }

    private fun String.trimHex(): String {
        return lowercase().removePrefix("0x").trimStart('0').ifEmpty { "0" }
    }
}

/** Minimal LZ4 Frame codec sufficient for Android mkdtboimg compression flag 3. */
private object Lz4FrameCodec {
    private const val MAGIC = 0x184D2204
    private const val SKIPPABLE_BASE = 0x184D2A50
    private const val SKIPPABLE_MASK = 0xFFFFFFF0.toInt()

    fun decompress(frame: ByteArray): ByteArray {
        val input = LeReader(frame)
        val magic = input.readInt()
        require((magic and SKIPPABLE_MASK) != SKIPPABLE_BASE) { "不支持 LZ4 skippable frame" }
        require(magic == MAGIC) { "LZ4 frame magic 无效：0x${magic.toUInt().toString(16)}" }

        val flg = input.readU8()
        val bd = input.readU8()
        require((flg ushr 6) == 1) { "不支持的 LZ4 frame version" }
        require((flg and 0x02) == 0) { "LZ4 FLG reserved bit 非 0" }
        require((bd and 0x8f) == 0) { "LZ4 BD reserved bits 非 0" }

        val blockIndependent = (flg and 0x20) != 0
        val blockChecksum = (flg and 0x10) != 0
        val contentSizePresent = (flg and 0x08) != 0
        val contentChecksum = (flg and 0x04) != 0
        val dictIdPresent = (flg and 0x01) != 0

        val descriptor = ByteArrayOutputStream().apply {
            write(flg)
            write(bd)
        }

        val expectedContentSize = if (contentSizePresent) {
            val raw = input.readBytes(8)
            descriptor.write(raw)
            littleEndianLong(raw)
        } else {
            null
        }

        if (dictIdPresent) {
            val raw = input.readBytes(4)
            descriptor.write(raw)
            val dictId = littleEndianInt(raw).toLong() and 0xffffffffL
            error("不支持带 Dictionary ID 的 LZ4 frame：$dictId")
        }

        val headerChecksum = input.readU8()
        val calculatedHeaderChecksum = (xxHash32(descriptor.toByteArray()) ushr 8) and 0xff
        require(headerChecksum == calculatedHeaderChecksum) { "LZ4 frame header checksum 不匹配" }

        val output = ByteAccumulator(
            expectedContentSize?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()?.coerceAtLeast(256) ?: 4096
        )
        var blockStart = 0

        while (true) {
            val blockSizeField = input.readInt()
            if (blockSizeField == 0) break

            val uncompressed = blockSizeField < 0
            val blockSize = blockSizeField and 0x7fffffff
            require(blockSize > 0 && blockSize <= 4 * 1024 * 1024) { "异常的 LZ4 block size=$blockSize" }
            val block = input.readBytes(blockSize)

            if (blockChecksum) {
                val expected = input.readInt()
                val actual = xxHash32(block)
                require(expected == actual) { "LZ4 block checksum 不匹配" }
            }

            if (uncompressed) {
                output.append(block)
            } else {
                if (blockIndependent) blockStart = output.size
                decompressBlock(block, output, blockStart)
            }
        }

        if (contentChecksum) {
            val expected = input.readInt()
            val actual = xxHash32(output.toByteArray())
            require(expected == actual) { "LZ4 content checksum 不匹配" }
        }
        require(input.remaining == 0) { "LZ4 frame 尾部存在 ${input.remaining} 个未解析字节" }

        val result = output.toByteArray()
        expectedContentSize?.let {
            require(it == result.size.toLong()) {
                "LZ4 content size 不匹配：header=$it actual=${result.size}"
            }
        }
        return result
    }

    fun compressAsUncompressedFrame(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64)
        writeLeInt(out, MAGIC)

        // Version=01, block independent=1, content size present=1.
        val flg = 0x68
        // Maximum block size code 7 => 4 MiB.
        val bd = 0x70
        val descriptor = ByteArrayOutputStream(10).apply {
            write(flg)
            write(bd)
            writeLeLong(this, data.size.toLong())
        }.toByteArray()

        out.write(descriptor)
        out.write((xxHash32(descriptor) ushr 8) and 0xff)

        var offset = 0
        val blockMax = 4 * 1024 * 1024
        while (offset < data.size) {
            val length = min(blockMax, data.size - offset)
            writeLeInt(out, length or Int.MIN_VALUE)
            out.write(data, offset, length)
            offset += length
        }
        writeLeInt(out, 0)
        return out.toByteArray()
    }

    private fun decompressBlock(block: ByteArray, output: ByteAccumulator, blockStart: Int) {
        var p = 0
        while (p < block.size) {
            val token = block[p++].toInt() and 0xff
            var literalLength = token ushr 4
            if (literalLength == 15) {
                while (true) {
                    require(p < block.size) { "LZ4 literal length 截断" }
                    val value = block[p++].toInt() and 0xff
                    literalLength += value
                    if (value != 255) break
                }
            }

            require(p + literalLength <= block.size) { "LZ4 literal 数据越界" }
            output.append(block, p, literalLength)
            p += literalLength
            if (p == block.size) break

            require(p + 2 <= block.size) { "LZ4 match offset 截断" }
            val matchOffset = (block[p].toInt() and 0xff) or ((block[p + 1].toInt() and 0xff) shl 8)
            p += 2
            require(matchOffset > 0) { "LZ4 match offset=0" }
            require(output.size - matchOffset >= blockStart) { "LZ4 match offset 超出可用历史窗口" }

            var matchLength = token and 0x0f
            if (matchLength == 15) {
                while (true) {
                    require(p < block.size) { "LZ4 match length 截断" }
                    val value = block[p++].toInt() and 0xff
                    matchLength += value
                    if (value != 255) break
                }
            }
            matchLength += 4
            output.copyFromHistory(matchOffset, matchLength)
        }
    }

    private fun xxHash32(data: ByteArray, seed: Int = 0): Int {
        val prime1 = 0x9E3779B1.toInt()
        val prime2 = 0x85EBCA77.toInt()
        val prime3 = 0xC2B2AE3D.toInt()
        val prime4 = 0x27D4EB2F
        val prime5 = 0x165667B1

        var index = 0
        var hash: Int
        if (data.size >= 16) {
            var v1 = seed + prime1 + prime2
            var v2 = seed + prime2
            var v3 = seed
            var v4 = seed - prime1
            val limit = data.size - 16
            while (index <= limit) {
                v1 = round(v1, readLeInt(data, index), prime1, prime2); index += 4
                v2 = round(v2, readLeInt(data, index), prime1, prime2); index += 4
                v3 = round(v3, readLeInt(data, index), prime1, prime2); index += 4
                v4 = round(v4, readLeInt(data, index), prime1, prime2); index += 4
            }
            hash = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7) +
                Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18)
        } else {
            hash = seed + prime5
        }

        hash += data.size
        while (index <= data.size - 4) {
            hash += readLeInt(data, index) * prime3
            hash = Integer.rotateLeft(hash, 17) * prime4
            index += 4
        }
        while (index < data.size) {
            hash += (data[index].toInt() and 0xff) * prime5
            hash = Integer.rotateLeft(hash, 11) * prime1
            index++
        }

        hash = hash xor (hash ushr 15)
        hash *= prime2
        hash = hash xor (hash ushr 13)
        hash *= prime3
        hash = hash xor (hash ushr 16)
        return hash
    }

    private fun round(acc: Int, input: Int, prime1: Int, prime2: Int): Int {
        var value = acc + input * prime2
        value = Integer.rotateLeft(value, 13)
        value *= prime1
        return value
    }

    private fun readLeInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun littleEndianInt(raw: ByteArray): Int = readLeInt(raw, 0)

    private fun littleEndianLong(raw: ByteArray): Long {
        require(raw.size == 8)
        var result = 0L
        for (i in 0..7) result = result or ((raw[i].toLong() and 0xffL) shl (8 * i))
        require(result >= 0) { "LZ4 content size 超出 Long 范围" }
        return result
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun writeLeLong(out: ByteArrayOutputStream, value: Long) {
        repeat(8) { i -> out.write(((value ushr (8 * i)) and 0xff).toInt()) }
    }

    private class LeReader(private val data: ByteArray) {
        private var pos = 0
        val remaining: Int get() = data.size - pos

        fun readU8(): Int {
            require(pos < data.size) { "LZ4 frame 截断" }
            return data[pos++].toInt() and 0xff
        }

        fun readInt(): Int {
            require(pos + 4 <= data.size) { "LZ4 frame 截断" }
            val value = readLeInt(data, pos)
            pos += 4
            return value
        }

        fun readBytes(count: Int): ByteArray {
            require(count >= 0 && pos + count <= data.size) { "LZ4 frame 截断" }
            return data.copyOfRange(pos, pos + count).also { pos += count }
        }
    }

    private class ByteAccumulator(initialCapacity: Int) {
        private var buffer = ByteArray(max(256, initialCapacity))
        var size: Int = 0
            private set

        fun append(bytes: ByteArray) = append(bytes, 0, bytes.size)

        fun append(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            ensure(size + length)
            bytes.copyInto(buffer, size, offset, offset + length)
            size += length
        }

        fun copyFromHistory(offset: Int, length: Int) {
            require(offset in 1..size)
            ensure(size + length)
            repeat(length) {
                buffer[size] = buffer[size - offset]
                size++
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(required: Int) {
            if (required <= buffer.size) return
            var next = buffer.size
            while (next < required) {
                next = (next * 2).coerceAtMost(Int.MAX_VALUE - 8)
                require(next >= required || next > buffer.size) { "解压数据过大" }
            }
            buffer = buffer.copyOf(next)
        }
    }
}
