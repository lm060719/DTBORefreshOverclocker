package io.mo.dtbooverclocker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class AppLoggerTest {

    @Test
    fun testLogWritingAndSize() {
        val tempDir = Files.createTempDirectory("app_logger_test").toFile()
        try {
            AppLogger.initWithDir(tempDir)
            AppLogger.log("[INFO] 单元测试启动")
            AppLogger.log(LogLevel.WARN, "Core", "测试警告信息")
            AppLogger.log("[CRITICAL] 严重错误模拟")

            val files = AppLogger.getLogFiles()
            assertTrue("应至少存在 1 个日志文件", files.isNotEmpty())
            assertTrue("日志总大小应大于 0", AppLogger.getTotalLogSize() > 0)

            val content = files.first().readText()
            assertTrue(content.contains("单元测试启动"))
            assertTrue(content.contains("[WARN] [Core] 测试警告信息"))
            assertTrue(content.contains("[CRITICAL]"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testExportLogs() {
        val tempDir = Files.createTempDirectory("app_logger_export").toFile()
        try {
            AppLogger.initWithDir(tempDir)
            AppLogger.log("[INFO] 测试导出流水账行 1")
            AppLogger.log("[OK] 测试导出流水账行 2")

            val baos = ByteArrayOutputStream()
            AppLogger.exportLogs(baos)
            val exportedText = baos.toString(Charsets.UTF_8.name())

            assertTrue(exportedText.contains("DTBO Refresh Overclocker Diagnostic Export Log"))
            assertTrue(exportedText.contains("测试导出流水账行 1"))
            assertTrue(exportedText.contains("测试导出流水账行 2"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLogRotationAndClear() {
        val tempDir = Files.createTempDirectory("app_logger_rotation").toFile()
        try {
            // Create 7 dummy log files
            for (i in 1..7) {
                val dummy = File(tempDir, "dtbo_log_2026010${i}_000000.log")
                dummy.writeText("Dummy log line $i\n")
                dummy.setLastModified(1000000L + i * 1000L)
            }

            // Initializing should rotate and keep <= 5 files
            AppLogger.initWithDir(tempDir)
            val filesAfterInit = AppLogger.getLogFiles()
            assertTrue("日志文件数量应受最大上限限制 (<= 5)", filesAfterInit.size <= 5)

            // Clear logs
            val clearResult = AppLogger.clearAllLogs()
            assertTrue(clearResult)
            val filesAfterClear = AppLogger.getLogFiles()
            assertEquals("清空后应只保留 1 个新的空白会话文件", 1, filesAfterClear.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

