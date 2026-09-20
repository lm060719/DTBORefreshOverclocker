package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.util.HashUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Preserves the partition dump layout, including identical AVB footer copies and trailing padding.
 * Only unsigned hash descriptors can be updated without a signing key. Unknown nonzero trailers
 * and signed vbmeta fail closed instead of silently producing an incomplete flashable image.
 */
object AvbImageEnvelope {
    data class Layout(
        val footer: Int, val vbmeta: Int, val size: Int,
        val originalSize: Int, val major: Long, val minor: Long
    )
    data class Inspection(
        val containerSize: Int,
        val dtboTotalSize: Int,
        val sha256: String,
        val layout: Layout?,
        val magicCandidates: Int,
        val validFooters: Int,
        // Sorted by position; all accepted copies describe exactly the same vbmeta and payload.
        val footerOffsets: List<Int>
    ) {
        // A zero-padded image without AVB does not establish a logical envelope boundary.
        val logicalImageSize: Int? get() = layout?.let { it.footer + 64 }
            ?: dtboTotalSize.takeIf { it == containerSize }
    }
    private data class HashField(val descriptor: Int, val digest: Int, val size: Int,
                                 val algorithm: String, val salt: ByteArray)

    fun rebuild(
        original: ByteArray, originalTotal: Int, payload: ByteArray,
        logSink: (String) -> Unit = {}
    ): ByteArray {
        val inspection = validate(original, originalTotal, logSink, "REBUILD_ORIGINAL")
        val layout = inspection.layout
        if (layout == null) {
            require((originalTotal until original.size).all { original[it] == 0.toByte() }) {
                "镜像尾部含无法识别的数据，不能丢弃后直接打包"
            }
            // A bare DTBO is allowed to grow; a padded partition dump keeps its capacity.
            require(original.size == originalTotal || payload.size <= original.size) { "新 DTBO 超出原分区镜像容量" }
            return payload.copyOf(if (original.size == originalTotal) payload.size else original.size).also {
                validate(it, payload.size, logSink, "REBUILD_OUTPUT")
            }
        }
        val vbmeta = original.copyOfRange(layout.vbmeta, layout.vbmeta + layout.size)
        val fields = hashFields(vbmeta)
        val vb = buffer(vbmeta)
        fields.forEach { field ->
            vb.putLong(field.descriptor + 16, payload.size.toLong())
            digest(field, payload, payload.size).copyInto(vbmeta, field.digest)
        }
        // AVB uses 4096-byte image alignment independently of the DTBO table's page_size.
        val newOffset = ((payload.size.toLong() + 4095) / 4096 * 4096)
        require(newOffset + vbmeta.size <= inspection.footerOffsets.first()) { "新 DTBO 与 AVB 尾部重叠，超出可用容量" }
        val result = ByteArray(original.size)
        payload.copyInto(result)
        vbmeta.copyInto(result, newOffset.toInt())
        val outputBuffer = buffer(result)
        inspection.footerOffsets.forEach { footer ->
            original.copyInto(result, footer, footer, footer + 64)
            outputBuffer.putLong(footer + 12, payload.size.toLong())
            outputBuffer.putLong(footer + 20, newOffset)
        }
        validate(result, payload.size, logSink, "REBUILD_OUTPUT")
        return result
    }

    fun validate(
        bytes: ByteArray, total: Int, logSink: (String) -> Unit = {}, stage: String = "VALIDATE"
    ): Inspection {
        val inspection = inspect(bytes, total, logSink, stage)
        val layout = inspection.layout ?: return inspection
        try {
            require(layout.major == 1L && layout.minor == 0L) { "不支持的 AVB footer 版本 ${layout.major}.${layout.minor}" }
            require(layout.originalSize == total) { "AVB footer 原始大小与 DTBO 不匹配" }
            val vbmeta = bytes.copyOfRange(layout.vbmeta, layout.vbmeta + layout.size)
            val b = buffer(vbmeta)
            require(b.getInt(4) == 1 && b.getInt(8) in 0..3) { "不支持的 AVB vbmeta required version" }
            hashFields(vbmeta).forEach { field ->
                require(b.getLong(field.descriptor + 16) == total.toLong()) {
                    "AVB 哈希覆盖范围与 DTBO total_size 不一致，暂不支持重建此布局"
                }
                require(digest(field, bytes, total).contentEquals(vbmeta.copyOfRange(field.digest, field.digest + field.size))) {
                    "AVB 摘要校验失败"
                }
            }
        } catch (e: IllegalArgumentException) {
            val message = "[AVB][$stage] ${e.message}; footerOffset=${layout.footer}, input_sha256=${inspection.sha256}"
            logSink("[ERROR] $message")
            throw IllegalArgumentException(message, e)
        }
        return inspection
    }

    /** Recognizes structure independently of signing/descriptor support; never drops unknown trailers. */
    fun inspect(
        bytes: ByteArray, total: Int, logSink: (String) -> Unit = {}, stage: String = "INSPECT"
    ): Inspection {
        val sha256 = HashUtils.sha256(bytes)
        val input = "input_size=${bytes.size}, dtbo_total_size=$total, input_sha256=$sha256"
        logSink("[AVB][$stage] $input")
        var candidates = 0
        var valid = 0
        var selected: Layout? = null
        val layouts = mutableListOf<Layout>()
        val offsets = mutableListOf<Int>()
        try {
            require(total in 32..bytes.size) { "无效的 DTBO 长度" }
            // Include truncated magic near EOF in diagnostics, and avoid allocating a list per byte.
            for (offset in total..(bytes.size - 4)) {
                if (bytes[offset] != 0x41.toByte() || bytes[offset + 1] != 0x56.toByte() ||
                    bytes[offset + 2] != 0x42.toByte() || bytes[offset + 3] != 0x66.toByte()) continue
                candidates++
                val details = if (bytes.size - offset >= 64) buffer(bytes).let {
                    "version=${it.getInt(offset + 4).toUInt()}.${it.getInt(offset + 8).toUInt()}, " +
                        "originalImageSize=${it.getLong(offset + 12)}, vbmetaOffset=${it.getLong(offset + 20)}, " +
                        "vbmetaSize=${it.getLong(offset + 28)}"
                } else "truncated=true"
                val status = try {
                    val layout = parseCandidate(bytes, total, offset)
                    valid++
                    layouts += layout
                    selected = layout
                    "structuralValid=true"
                } catch (e: IllegalArgumentException) {
                    "structuralValid=false, reason=${e.message}"
                }
                // Bound diagnostics for corrupt files containing millions of magic sequences.
                if (offsets.size < 32) {
                    offsets += offset
                    logSink("[AVB][$stage] candidate offset=$offset (0x${offset.toString(16)}), $details, $status")
                }
            }
            logSink("[AVB][$stage] magicCandidates=$candidates, validFooters=$valid" +
                if (candidates > offsets.size) ", candidate details limited to ${offsets.size}" else "")
            if (candidates == 0) {
                require((total until bytes.size).all { bytes[it] == 0.toByte() }) {
                    "未找到 AVBf magic，镜像含未知尾部数据，不能安全重建"
                }
            } else {
                require(valid > 0) { "找到 AVBf magic，但没有任何候选通过结构验证" }
                val layout = requireNotNull(selected)
                // Repeated footer bytes are unambiguous only when every field and vbmeta location
                // match. Keep every copy, using the outermost footer as the logical boundary.
                require(layouts.all { it.copy(footer = layout.footer) == layout }) {
                    "找到多个有效 AVB footer，且内容或指向的 vbmeta 不一致，无法安全确定目标"
                }
                require((total until layout.vbmeta).all { bytes[it] == 0.toByte() }) {
                    "AVB 区域外存在未知数据，无法安全重建"
                }
                var paddingStart = layout.vbmeta + layout.size
                layouts.forEach { copy ->
                    require(copy.footer >= paddingStart &&
                        (paddingStart until copy.footer).all { bytes[it] == 0.toByte() }) {
                        "AVB 区域外存在未知数据或 footer 重叠，无法安全重建"
                    }
                    paddingStart = copy.footer + 64
                }
                require((paddingStart until bytes.size).all { bytes[it] == 0.toByte() }) {
                    "AVB 区域外存在未知数据，无法安全重建"
                }
                if (valid > 1) {
                    logSink("[AVB][$stage] identicalFooterCopies=$valid, preserving all copies at offsets=" +
                        layouts.take(32).map { it.footer })
                }
                logSink("[AVB][$stage] selectedFooter=${layout.footer}, logicalImageSize=${layout.footer + 64}, " +
                    "containerSize=${bytes.size}")
            }
        } catch (e: IllegalArgumentException) {
            val message = "[AVB][$stage] ${e.message}; $input, magicCandidates=$candidates, " +
                "validFooters=$valid, candidateOffsets=$offsets"
            logSink("[ERROR] $message")
            throw IllegalArgumentException(message, e)
        }
        return Inspection(bytes.size, total, sha256, selected, candidates, valid, layouts.map { it.footer })
    }

    private fun parseCandidate(bytes: ByteArray, total: Int, f: Int): Layout {
        require(bytes.size - f >= 64) { "AVB footer 截断" }
        val b = buffer(bytes)
        val major = b.getInt(f + 4).toLong() and 0xffffffffL
        val minor = b.getInt(f + 8).toLong() and 0xffffffffL
        require(major > 0) { "无效的 AVB footer 版本" }
        require((f + 36 until f + 64).all { bytes[it] == 0.toByte() }) { "AVB footer 保留区非零" }
        val originalSize = bounded(b.getLong(f + 12), f)
        require(originalSize >= 32) { "AVB original_image_size 无效" }
        val offset = bounded(b.getLong(f + 20), bytes.size)
        val size = bounded(b.getLong(f + 28), bytes.size)
        require(size >= 256 && offset >= maxOf(total, originalSize) && offset.toLong() + size <= f) { "AVB vbmeta 范围无效" }
        require(b.getInt(offset) == 0x41564230) { "AVB vbmeta magic 无效" }
        val authSize = bounded(b.getLong(offset + 12), size - 256)
        val auxSize = bounded(b.getLong(offset + 20), size - 256 - authSize)
        require(authSize % 64 == 0 && auxSize % 64 == 0 && 256L + authSize + auxSize == size.toLong()) {
            "AVB authentication/auxiliary 数据大小无效"
        }
        fun checkRange(field: Int, limit: Int): Int {
            val start = bounded(b.getLong(offset + field), limit)
            return bounded(b.getLong(offset + field + 8), limit - start)
        }
        checkRange(32, authSize) // Hash
        checkRange(48, authSize) // Signature
        checkRange(64, auxSize) // Public key
        checkRange(80, auxSize) // Public key metadata
        val descriptorSize = checkRange(96, auxSize)
        var p = offset + 256 + authSize + bounded(b.getLong(offset + 96), auxSize)
        val end = p + descriptorSize
        while (p < end) {
            require(end - p >= 16) { "AVB descriptor 头部截断" }
            val remaining = bounded(b.getLong(p + 8), end - p - 16)
            require(remaining % 8 == 0) { "AVB descriptor 未对齐" }
            p += 16 + remaining
        }
        return Layout(f, offset, size, originalSize, major, minor)
    }

    private fun hashFields(v: ByteArray): List<HashField> {
        val b = buffer(v)
        require(b.getInt(28) == 0 && b.getLong(12) == 0L) {
            "该镜像包含 AVB 签名，修改后需要原签名密钥；已阻止生成无效镜像"
        }
        val auxiliarySize = bounded(b.getLong(20), v.size - 256)
        require(256 + auxiliarySize == v.size) { "AVB 辅助数据大小不匹配" }
        val descriptorsOffset = bounded(b.getLong(96), auxiliarySize)
        val descriptorsSize = bounded(b.getLong(104), auxiliarySize - descriptorsOffset)
        var p = 256 + descriptorsOffset
        val end = p + descriptorsSize
        val fields = mutableListOf<HashField>()
        while (p < end) {
            require(end - p >= 16) { "AVB descriptor 头部截断" }
            val tag = b.getLong(p)
            val remaining = bounded(b.getLong(p + 8), end - p - 16)
            require(remaining % 8 == 0) { "AVB descriptor 未对齐" }
            val next = p + 16 + remaining
            when (tag) {
                0L -> Unit // Property descriptor: preserve the original bytes (e.g. build fingerprint).
                2L -> {
                    require(remaining >= 116) { "AVB hash descriptor 截断" }
                    val nameSize = bounded(b.getInt(p + 56).toLong(), next - p - 132)
                    val saltSize = bounded(b.getInt(p + 60).toLong(), next - p - 132 - nameSize)
                    val digestSize = bounded(b.getInt(p + 64).toLong(), next - p - 132 - nameSize - saltSize)
                    val name = String(v, p + 132, nameSize, Charsets.US_ASCII)
                    require(name == "dtbo") { "不支持修改分区名称为 $name 的 AVB 描述符" }
                    val algorithm = String(v, p + 24, 32, Charsets.US_ASCII).trimEnd('\u0000')
                    val javaAlgorithm = when (algorithm) {
                        "sha256" -> "SHA-256"
                        "sha512" -> "SHA-512"
                        else -> throw IllegalArgumentException("不支持的 AVB 哈希算法：$algorithm")
                    }
                    require(digestSize == MessageDigest.getInstance(javaAlgorithm).digestLength) { "AVB 摘要长度无效" }
                    val saltStart = p + 132 + nameSize
                    fields += HashField(p, saltStart + saltSize, digestSize, javaAlgorithm,
                        v.copyOfRange(saltStart, saltStart + saltSize))
                }
                else -> throw IllegalArgumentException("暂不支持重建 AVB descriptor 类型 $tag")
            }
            p = next
        }
        require(fields.size == 1) { "需要唯一的 dtbo AVB hash descriptor" }
        return fields
    }

    private fun bounded(value: Long, limit: Int): Int {
        require(value >= 0 && value <= limit.toLong()) { "AVB 字段越界：$value" }
        return value.toInt()
    }

    private fun digest(field: HashField, bytes: ByteArray, size: Int): ByteArray =
        MessageDigest.getInstance(field.algorithm).apply {
            update(field.salt)
            update(bytes, 0, size)
        }.digest()

    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
}
