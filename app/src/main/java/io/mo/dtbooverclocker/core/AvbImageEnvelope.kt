package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.AvbProtectionState
import io.mo.dtbooverclocker.util.HashUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Preserves the partition dump layout, including identical AVB footer copies and trailing padding.
 * Only unsigned hash descriptors can be updated without a signing key. Unknown nonzero trailers
 * around an AVB envelope and signed vbmeta fail closed instead of silently producing an incomplete
 * flashable image. Without any AVB structure, bytes after total_size are leftovers of an older DTBO
 * (seen on Realme dumps); the loader never reads them, so rebuild zero-fills them.
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
        val footerOffsets: List<Int>,
        val protectionState: AvbProtectionState,
        val algorithm: String?,
        // Nonzero bytes after total_size in an image without any AVB structure.
        val staleTailBytes: Int = 0
    ) {
        // A zero-padded image without AVB does not establish a logical envelope boundary.
        val logicalImageSize: Int? get() = layout?.let { it.footer + 64 }
            ?: dtboTotalSize.takeIf { it == containerSize }
    }

    private data class VbmetaHeader(
        val authenticationSize: Int,
        val auxiliarySize: Int,
        val algorithmType: Int,
        val hashOffset: Int,
        val hashSize: Int,
        val signatureOffset: Int,
        val signatureSize: Int,
        val descriptorsOffset: Int,
        val descriptorsSize: Int
    ) {
        val isSigned: Boolean
            get() = algorithmType != 0 || authenticationSize != 0
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
            if (inspection.staleTailBytes > 0) {
                logSink("[WARN][AVB][REBUILD] 原镜像 total_size 之后的 ${inspection.staleTailBytes} 个旧 DTBO 残留字节已在输出中清零")
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
    ): Inspection = validateInternal(bytes, total, logSink, stage, allowSigned = false)

    /**
     * Import-time validation is intentionally less restrictive than rebuild validation.
     * A correctly formed signed AVB container is safe to inspect and edit in the workspace,
     * but it still cannot be rebuilt without the signing key.
     */
    fun validateForAnalysis(
        bytes: ByteArray, total: Int, logSink: (String) -> Unit = {}, stage: String = "ANALYZE"
    ): Inspection = validateInternal(bytes, total, logSink, stage, allowSigned = true)

    private fun validateInternal(
        bytes: ByteArray,
        total: Int,
        logSink: (String) -> Unit,
        stage: String,
        allowSigned: Boolean
    ): Inspection {
        val inspection = inspect(bytes, total, logSink, stage)
        val layout = inspection.layout ?: return inspection
        try {
            require(layout.major == 1L && layout.minor == 0L) { "不支持的 AVB footer 版本 ${layout.major}.${layout.minor}" }
            require(layout.originalSize == total) { "AVB footer 原始大小与 DTBO 不匹配" }
            val vbmeta = bytes.copyOfRange(layout.vbmeta, layout.vbmeta + layout.size)
            val b = buffer(vbmeta)
            require(b.getInt(4) == 1 && b.getInt(8) in 0..3) { "不支持的 AVB vbmeta required version" }
            val header = parseVbmetaHeader(vbmeta)
            if (header.isSigned) {
                require(allowSigned) {
                    "该镜像包含 AVB 签名，修改后需要原签名密钥；已阻止生成无效镜像"
                }
                validateAuthenticationHash(vbmeta, header)
                logSink(
                    "[WARN][AVB][$stage] 检测到已签名 AVB (${algorithmName(header.algorithmType)}); " +
                        "允许进入设备树工作区，但修改后的镜像仍需原签名密钥才能生成有效签名"
                )
            }
            hashFields(vbmeta, allowUnknownDescriptors = allowSigned).forEach { field ->
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
        var staleTailBytes = 0
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
                staleTailBytes = (total until bytes.size).count { bytes[it] != 0.toByte() }
                if (staleTailBytes > 0) {
                    // A vbmeta blob without its footer is a damaged AVB envelope, not DTBO leftovers.
                    require(indexOf(bytes, VBMETA_MAGIC, total) < 0) {
                        "未找到 AVBf footer，但尾部含 AVB0 vbmeta 结构，疑似 AVB 尾部损坏，不能安全重建"
                    }
                    val first = (total until bytes.size).first { bytes[it] != 0.toByte() }
                    val last = (bytes.size - 1 downTo total).first { bytes[it] != 0.toByte() }
                    logSink("[WARN][AVB][$stage] 无 AVB footer，total_size 之后发现 $staleTailBytes 个非零残留字节 " +
                        "(0x${first.toString(16)}..0x${last.toString(16)})；DTBO 加载只读取 total_size 以内，打包时将清零")
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
        val selectedAlgorithmType = selected?.let { layout ->
            buffer(bytes).getInt(layout.vbmeta + 28)
        }
        val selectedAuthenticationSize = selected?.let { layout ->
            buffer(bytes).getLong(layout.vbmeta + 12)
        } ?: 0L
        val protectionState = when {
            selected == null -> AvbProtectionState.NONE
            selectedAlgorithmType != 0 || selectedAuthenticationSize != 0L -> AvbProtectionState.SIGNED
            else -> AvbProtectionState.UNSIGNED
        }
        return Inspection(
            bytes.size,
            total,
            sha256,
            selected,
            candidates,
            valid,
            layouts.map { it.footer },
            protectionState,
            selectedAlgorithmType?.let(::algorithmName),
            staleTailBytes
        )
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

    private fun parseVbmetaHeader(v: ByteArray): VbmetaHeader {
        require(v.size >= 256) { "AVB vbmeta 头部截断" }
        val b = buffer(v)
        val authenticationSize = bounded(b.getLong(12), v.size - 256)
        val auxiliarySize = bounded(b.getLong(20), v.size - 256 - authenticationSize)
        require(256L + authenticationSize + auxiliarySize == v.size.toLong()) {
            "AVB authentication/auxiliary 数据大小与 vbmeta 不匹配"
        }

        fun range(field: Int, limit: Int): Pair<Int, Int> {
            val start = bounded(b.getLong(field), limit)
            val size = bounded(b.getLong(field + 8), limit - start)
            return start to size
        }

        val (hashOffset, hashSize) = range(32, authenticationSize)
        val (signatureOffset, signatureSize) = range(48, authenticationSize)
        range(64, auxiliarySize)
        range(80, auxiliarySize)
        val (descriptorsOffset, descriptorsSize) = range(96, auxiliarySize)

        return VbmetaHeader(
            authenticationSize = authenticationSize,
            auxiliarySize = auxiliarySize,
            algorithmType = b.getInt(28),
            hashOffset = hashOffset,
            hashSize = hashSize,
            signatureOffset = signatureOffset,
            signatureSize = signatureSize,
            descriptorsOffset = descriptorsOffset,
            descriptorsSize = descriptorsSize
        )
    }

    private fun validateAuthenticationHash(v: ByteArray, header: VbmetaHeader) {
        val digestAlgorithm = authenticationDigestAlgorithm(header.algorithmType) ?: return
        val expectedSize = MessageDigest.getInstance(digestAlgorithm).digestLength
        require(header.hashSize == expectedSize) {
            "AVB 签名认证摘要长度无效：${header.hashSize}"
        }
        require(header.signatureSize > 0) { "AVB 标记为已签名，但签名数据为空" }

        val authBase = 256
        val auxBase = authBase + header.authenticationSize
        val storedHash = v.copyOfRange(
            authBase + header.hashOffset,
            authBase + header.hashOffset + header.hashSize
        )
        val calculated = MessageDigest.getInstance(digestAlgorithm).apply {
            update(v, 0, 256)
            update(v, auxBase, header.auxiliarySize)
        }.digest()
        require(storedHash.contentEquals(calculated)) { "AVB vbmeta 认证摘要校验失败" }
    }

    private fun authenticationDigestAlgorithm(algorithmType: Int): String? = when (algorithmType) {
        1, 2, 3 -> "SHA-256"
        4, 5, 6 -> "SHA-512"
        else -> null
    }

    private fun algorithmName(algorithmType: Int): String = when (algorithmType) {
        0 -> "NONE"
        1 -> "SHA256_RSA2048"
        2 -> "SHA256_RSA4096"
        3 -> "SHA256_RSA8192"
        4 -> "SHA512_RSA2048"
        5 -> "SHA512_RSA4096"
        6 -> "SHA512_RSA8192"
        else -> "AVB_ALGORITHM_$algorithmType"
    }

    private fun hashFields(v: ByteArray, allowUnknownDescriptors: Boolean = false): List<HashField> {
        val b = buffer(v)
        val header = parseVbmetaHeader(v)
        val auxiliaryBase = 256 + header.authenticationSize
        var p = auxiliaryBase + header.descriptorsOffset
        val end = p + header.descriptorsSize
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
                else -> if (!allowUnknownDescriptors) {
                    throw IllegalArgumentException("暂不支持重建 AVB descriptor 类型 $tag")
                }
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

    private val VBMETA_MAGIC = byteArrayOf(0x41, 0x56, 0x42, 0x30) // "AVB0"

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        for (i in from..bytes.size - pattern.size) {
            if (pattern.indices.all { bytes[i + it] == pattern[it] }) return i
        }
        return -1
    }
}
