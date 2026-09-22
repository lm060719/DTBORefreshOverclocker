package io.mo.dtbooverclocker.core.devicetree

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DtsFileTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun analysisFailureRestoresEveryEntryByteForByte() {
        val first = temporary.newFile("entry_0.dts").apply { writeText("first\r\n") }
        val second = temporary.newFile("entry_1.dts").apply { writeText("second\n") }
        val originalFirst = first.readBytes()
        val originalSecond = second.readBytes()
        assertThrows(IllegalStateException::class.java) {
            DtsFileTransaction.commit(linkedMapOf(first to "new first", second to "new second")) {
                assertEquals("new first", first.readText())
                assertEquals("new second", second.readText())
                error("Injected analysis failure")
            }
        }
        assertArrayEquals(originalFirst, first.readBytes())
        assertArrayEquals(originalSecond, second.readBytes())
        assertEquals(2, temporary.root.listFiles()!!.size)
    }

    @Test
    fun failedPreparationNeverChangesEarlierEntries() {
        val first = temporary.newFile("entry_0.dts").apply { writeText("original") }
        val missing = File(temporary.root, "missing.dts")
        assertThrows(IllegalArgumentException::class.java) {
            DtsFileTransaction.commit(linkedMapOf(first to "new", missing to "new")) { fail("must not commit") }
        }
        assertEquals("original", first.readText())
        assertFalse(missing.exists())
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test
    fun successfulBatchKeepsChangesAndReturnsAnalysisResult() {
        val file = temporary.newFile("entry_0.dts").apply { writeText("old") }
        assertEquals("new", DtsFileTransaction.commit(mapOf(file to "new")) { file.readText() })
        assertEquals("new", file.readText())
        assertEquals(1, temporary.root.listFiles()!!.size)
    }
}
