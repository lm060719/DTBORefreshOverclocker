package io.mo.dtbooverclocker.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {
    fun sha256(file: File): String {
        file.inputStream().use { return sha256(it) }
    }

    fun sha256(input: InputStream, limit: Long? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit

        while (true) {
            val requested = if (remaining == null) {
                buffer.size
            } else {
                if (remaining <= 0L) break
                minOf(buffer.size.toLong(), remaining).toInt()
            }

            val read = input.read(buffer, 0, requested)
            if (read < 0) break
            digest.update(buffer, 0, read)
            if (remaining != null) remaining -= read
        }

        if (limit != null && remaining != 0L) {
            throw IllegalStateException("输入长度不足，无法计算指定长度的 SHA-256")
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
