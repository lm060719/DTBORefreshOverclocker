package io.mo.dtbooverclocker.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Preserves the partition dump layout, including AVB footers embedded before trailing padding.
 * Only unsigned hash descriptors can be updated without a signing key. Unknown nonzero trailers
 * and signed vbmeta fail closed instead of silently producing an incomplete flashable image.
 */
object AvbImageEnvelope {
    private data class Layout(val footer: Int, val vbmeta: Int, val size: Int)
    private data class HashField(val descriptor: Int, val digest: Int, val size: Int,
                                 val algorithm: String, val salt: ByteArray)

    fun rebuild(original: ByteArray, originalTotal: Int, payload: ByteArray): ByteArray {
        val layout = layout(original, originalTotal)
        if (layout == null) {
            require((originalTotal until original.size).all { original[it] == 0.toByte() }) {
                "镜像尾部含无法识别的数据，不能丢弃后直接打包"
            }
            // A bare DTBO is allowed to grow; a padded partition dump keeps its capacity.
            require(original.size == originalTotal || payload.size <= original.size) { "新 DTBO 超出原分区镜像容量" }
            return payload.copyOf(if (original.size == originalTotal) payload.size else original.size)
        }
        val vbmeta = original.copyOfRange(layout.vbmeta, layout.vbmeta + layout.size)
        val fields = hashFields(vbmeta)
        val vb = buffer(vbmeta)
        fields.forEach { field ->
            require(vb.getLong(field.descriptor + 16) == originalTotal.toLong()) {
                "AVB 哈希覆盖范围与 DTBO total_size 不一致，暂不支持重建此布局"
            }
            require(digest(field, original, originalTotal).contentEquals(
                vbmeta.copyOfRange(field.digest, field.digest + field.size))) { "原镜像 AVB 摘要不匹配" }
            vb.putLong(field.descriptor + 16, payload.size.toLong())
            digest(field, payload, payload.size).copyInto(vbmeta, field.digest)
        }
        // AVB uses 4096-byte image alignment independently of the DTBO table's page_size.
        val newOffset = ((payload.size.toLong() + 4095) / 4096 * 4096)
        require(newOffset + vbmeta.size <= layout.footer) { "新 DTBO 与 AVB 尾部重叠，超出可用容量" }
        val result = ByteArray(original.size)
        payload.copyInto(result)
        vbmeta.copyInto(result, newOffset.toInt())
        original.copyInto(result, layout.footer, layout.footer, layout.footer + 64)
        buffer(result).apply {
            putLong(layout.footer + 12, payload.size.toLong())
            putLong(layout.footer + 20, newOffset)
        }
        validate(result, payload.size)
        return result
    }

    fun validate(bytes: ByteArray, total: Int) {
        val layout = layout(bytes, total) ?: run {
            require((total until bytes.size).all { bytes[it] == 0.toByte() }) { "镜像含未知尾部数据" }
            return
        }
        val vbmeta = bytes.copyOfRange(layout.vbmeta, layout.vbmeta + layout.size)
        hashFields(vbmeta).forEach { field ->
            require(buffer(vbmeta).getLong(field.descriptor + 16) == total.toLong()) { "AVB 镜像长度不匹配" }
            require(digest(field, bytes, total).contentEquals(vbmeta.copyOfRange(field.digest, field.digest + field.size))) {
                "AVB 摘要校验失败"
            }
        }
    }

    private fun layout(bytes: ByteArray, total: Int): Layout? {
        require(total in 32..bytes.size) { "无效的 DTBO 长度" }
        val matches = (total..(bytes.size - 64)).filter {
            bytes[it] == 0x41.toByte() && bytes[it + 1] == 0x56.toByte() &&
                bytes[it + 2] == 0x42.toByte() && bytes[it + 3] == 0x66.toByte()
        }
        if (matches.isEmpty()) return null
        require(matches.size == 1) { "发现多个 AVB footer，无法确定镜像布局" }
        val f = matches.single()
        val b = buffer(bytes)
        require(b.getInt(f + 4) == 1 && b.getInt(f + 8) == 0) { "不支持的 AVB footer 版本" }
        require(b.getLong(f + 12) == total.toLong()) { "AVB footer 原始大小与 DTBO 不匹配" }
        val offset = bounded(b.getLong(f + 20), bytes.size)
        val size = bounded(b.getLong(f + 28), bytes.size)
        require(size >= 256 && offset >= total && offset.toLong() + size <= f) { "AVB vbmeta 范围无效" }
        require(b.getInt(offset) == 0x41564230) { "AVB vbmeta magic 无效" }
        require((total until offset).all { bytes[it] == 0.toByte() } &&
            (offset + size until f).all { bytes[it] == 0.toByte() } &&
            (f + 64 until bytes.size).all { bytes[it] == 0.toByte() }) { "AVB 区域外存在未知数据，无法安全重建" }
        return Layout(f, offset, size)
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
                        else -> error("不支持的 AVB 哈希算法：$algorithm")
                    }
                    require(digestSize == MessageDigest.getInstance(javaAlgorithm).digestLength) { "AVB 摘要长度无效" }
                    val saltStart = p + 132 + nameSize
                    fields += HashField(p, saltStart + saltSize, digestSize, javaAlgorithm,
                        v.copyOfRange(saltStart, saltStart + saltSize))
                }
                else -> error("暂不支持重建 AVB descriptor 类型 $tag")
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
