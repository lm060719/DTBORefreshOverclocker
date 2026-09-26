package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.util.HashUtils
import java.io.File

/**
 * 按 MD5 寻址的 DTBO 原始镜像缓存：同一镜像只保存一份 `<md5>.img`。
 *
 * 缓存文件只作为只读输入；analyze 会把它复制进独立工作区，编辑不会改动缓存本身。
 */
class ImageCache(private val dir: File) {
    data class CachedImage(val file: File, val md5: String, val reused: Boolean)

    /** 返回与 [md5] 一致且内容校验通过的缓存镜像；缓存文件损坏时删除并返回 null。 */
    fun find(md5: String): File? {
        val key = md5.lowercase()
        require(key.matches(MD5_PATTERN)) { "无效的 MD5：$md5" }
        val file = File(dir, "$key.img")
        if (!file.isFile) return null
        if (file.length() < MIN_IMAGE_SIZE || HashUtils.md5(file) != key) {
            file.delete()
            return null
        }
        return file
    }

    fun newIncomingFile(): File {
        dir.mkdirs()
        return File(dir, ".incoming_${System.nanoTime()}.tmp")
    }

    /** 把刚写入的临时文件纳入缓存：已有相同 MD5 的镜像时删除临时文件并复用缓存。 */
    fun admit(incoming: File): CachedImage {
        require(incoming.isFile && incoming.length() >= MIN_IMAGE_SIZE) { "镜像过小，不像有效 DTBO 镜像" }
        val md5 = HashUtils.md5(incoming)
        find(md5)?.takeIf { it.length() == incoming.length() }?.let { cached ->
            incoming.delete()
            return CachedImage(cached, md5, reused = true)
        }
        val target = File(dir, "$md5.img")
        if (!incoming.renameTo(target)) {
            incoming.copyTo(target, overwrite = true)
            incoming.delete()
        }
        return CachedImage(target, md5, reused = false)
    }

    companion object {
        const val MIN_IMAGE_SIZE = 32L
        private val MD5_PATTERN = Regex("^[0-9a-f]{32}$")
    }
}
