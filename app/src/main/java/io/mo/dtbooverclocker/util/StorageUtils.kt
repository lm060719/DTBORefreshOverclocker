package io.mo.dtbooverclocker.util

import android.content.Context
import java.io.File
import java.util.Locale

object StorageUtils {

    /**
     * Recursively calculates the total size in bytes of all files within a directory.
     */
    fun getDirectorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()

        var total = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            total += if (child.isDirectory) {
                getDirectorySize(child)
            } else {
                child.length()
            }
        }
        return total
    }

    /**
     * Deletes all contents inside a directory, leaving the directory itself intact.
     */
    fun clearDirectory(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return true
        val children = dir.listFiles() ?: return true
        var allDeleted = true
        for (child in children) {
            val deleted = if (child.isDirectory) {
                clearDirectory(child) && child.delete()
            } else {
                child.delete()
            }
            if (!deleted) allDeleted = false
        }
        return allDeleted
    }

    /**
     * Returns the total cache size of the application across internal and external cache directories.
     */
    fun getAppCacheSize(context: Context): Long {
        var size = getDirectorySize(context.cacheDir)
        try {
            context.externalCacheDir?.let {
                size += getDirectorySize(it)
            }
        } catch (_: Throwable) {
            // Ignore external storage access errors
        }
        try {
            size += getDirectorySize(context.codeCacheDir)
        } catch (_: Throwable) {
            // Ignore code cache errors
        }
        return size
    }

    /**
     * Clears all cached files in the internal and external cache directories.
     */
    fun clearAllCache(context: Context): Boolean {
        var success = clearDirectory(context.cacheDir)
        try {
            context.externalCacheDir?.let {
                val extSuccess = clearDirectory(it)
                success = success && extSuccess
            }
        } catch (_: Throwable) {
            // Ignore external storage errors
        }
        try {
            val codeSuccess = clearDirectory(context.codeCacheDir)
            success = success && codeSuccess
        } catch (_: Throwable) {
            // Ignore code cache errors
        }
        return success
    }

    /**
     * Formats bytes into a human-readable string (e.g., "12.34 MB", "512 KB", "0 B").
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return if (digitGroups == 0) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.2f %s", value, units[digitGroups])
        }
    }
}
