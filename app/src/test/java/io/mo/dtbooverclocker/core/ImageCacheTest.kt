package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.util.HashUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class ImageCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun incoming(cache: ImageCache, bytes: ByteArray) = cache.newIncomingFile().apply { writeBytes(bytes) }

    @Test fun sameContentIsStoredOnceAndReused() {
        val dir = tmp.newFolder("images")
        val cache = ImageCache(dir)
        val bytes = Random(1).nextBytes(10_000)

        val first = cache.admit(incoming(cache, bytes))
        val second = cache.admit(incoming(cache, bytes))

        assertFalse(first.reused)
        assertTrue(second.reused)
        assertEquals(first.file, second.file)
        assertEquals(HashUtils.md5(first.file), second.md5)
        assertEquals("${second.md5}.img", second.file.name)
        assertEquals("only one cached image, no leftover temp files", listOf(second.file.name), dir.list()!!.toList())
        assertArrayEquals(bytes, second.file.readBytes())
    }

    @Test fun differentContentGetsSeparateEntries() {
        val cache = ImageCache(tmp.newFolder("images"))
        val a = cache.admit(incoming(cache, Random(2).nextBytes(5000)))
        val b = cache.admit(incoming(cache, Random(3).nextBytes(5000)))
        assertFalse(b.reused)
        assertTrue(a.file != b.file && a.file.isFile && b.file.isFile)
    }

    @Test fun findLooksUpByMd5AndDropsCorruptedEntries() {
        val cache = ImageCache(tmp.newFolder("images"))
        val stored = cache.admit(incoming(cache, Random(4).nextBytes(5000)))

        assertEquals(stored.file, cache.find(stored.md5.uppercase()))
        assertNull(cache.find("0".repeat(32)))

        stored.file.appendBytes(byteArrayOf(1))
        assertNull(cache.find(stored.md5))
        assertFalse("corrupted cache entry removed", stored.file.exists())

        val again = cache.admit(incoming(cache, Random(4).nextBytes(5000)))
        assertFalse("corrupted entry is replaced, not reused", again.reused)
        assertEquals(stored.md5, HashUtils.md5(again.file))
    }

    @Test fun rejectsTinyImages() {
        val cache = ImageCache(tmp.newFolder("images"))
        val failure = runCatching { cache.admit(incoming(cache, ByteArray(8))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
