package io.mo.dtbooverclocker.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class HashUtilsMd5Test {

    @Test
    fun testMd5KnownVectors() {
        val emptyInput = ByteArrayInputStream(ByteArray(0))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", HashUtils.md5(emptyInput))

        val helloInput = ByteArrayInputStream("hello".toByteArray(Charsets.UTF_8))
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5(helloInput))

        val numInput = ByteArrayInputStream("123456".toByteArray(Charsets.UTF_8))
        assertEquals("e10adc3949ba59abbe56e057f20f883e", HashUtils.md5(numInput))
    }

    @Test
    fun testMd5File() {
        val tempFile = Files.createTempFile("hash_test", ".bin").toFile()
        try {
            tempFile.writeText("hello")
            assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testMd5StreamWithLimit() {
        val input = ByteArrayInputStream("hello world".toByteArray(Charsets.UTF_8))
        // Limit to first 5 bytes -> "hello"
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashUtils.md5(input, limit = 5L))
    }
}
