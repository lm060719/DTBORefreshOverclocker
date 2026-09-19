package io.mo.dtbooverclocker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageUtilsTest {

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", StorageUtils.formatFileSize(-10))
        assertEquals("0 B", StorageUtils.formatFileSize(0))
        assertEquals("512 B", StorageUtils.formatFileSize(512))
        assertEquals("1.00 KB", StorageUtils.formatFileSize(1024))
        assertEquals("1.50 KB", StorageUtils.formatFileSize(1536))
        assertEquals("1.00 MB", StorageUtils.formatFileSize(1024 * 1024))
        assertEquals("2.50 MB", StorageUtils.formatFileSize((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.00 GB", StorageUtils.formatFileSize(1024L * 1024L * 1024L))
    }

    @Test
    fun testGetDirectorySizeAndClearDirectory() {
        val tempDir = Files.createTempDirectory("storage_test").toFile()
        try {
            val subDir = File(tempDir, "sub").apply { mkdirs() }
            val file1 = File(tempDir, "file1.bin").apply { writeBytes(ByteArray(100)) }
            val file2 = File(subDir, "file2.bin").apply { writeBytes(ByteArray(250)) }

            val totalSize = StorageUtils.getDirectorySize(tempDir)
            assertEquals(350L, totalSize)

            val cleared = StorageUtils.clearDirectory(tempDir)
            assertTrue(cleared)
            assertTrue(tempDir.exists())
            assertEquals(0L, StorageUtils.getDirectorySize(tempDir))
            assertFalse(file1.exists())
            assertFalse(file2.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
