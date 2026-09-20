package io.mo.dtbooverclocker.util

import android.content.Context
import android.os.Build
import io.mo.dtbooverclocker.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    CRITICAL
}

object AppLogger {
    private const val MAX_LOG_FILES = 5
    private val lock = Any()

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Initializes the logger with the application context and starts a new session log file.
     */
    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        initWithDir(dir)
    }

    /**
     * Initializes the logger with an explicit directory.
     */
    fun initWithDir(dir: File) {
        synchronized(lock) {
            dir.mkdirs()
            logDir = dir
            rotateLogs(dir)
            val sessionName = "dtbo_log_${fileTimestampFormat.format(Date())}.log"
            currentLogFile = File(dir, sessionName).apply {
                if (!exists()) createNewFile()
            }
        }
    }

    /**
     * Records a diagnostic system baseline snapshot to the log.
     */
    fun logSystemBaseline(
        context: Context,
        rootDetail: String,
        slotLabel: String,
        blockDevice: String
    ) {
        val sb = StringBuilder()
        sb.appendLine("==================== SYSTEM BASELINE SNAPSHOT ====================")
        sb.appendLine("Timestamp: ${dateFormat.format(Date())}")
        sb.appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
            "buildType=${BuildConfig.BUILD_TYPE}, sourceId=${BuildConfig.SOURCE_ID}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT} / ${Build.DEVICE})")
        sb.appendLine("Board / Hardware: ${Build.BOARD} / ${Build.HARDWARE}")
        sb.appendLine("Android OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.ID})")
        sb.appendLine("Kernel: ${System.getProperty("os.version") ?: "unknown"}")
        sb.appendLine("Root Environment: $rootDetail")
        sb.appendLine("Slot Target: $slotLabel")
        sb.appendLine("Partition Block Path: $blockDevice")
        sb.appendLine("==================================================================")
        logRaw(sb.toString().trimEnd())
    }

    /**
     * Logs a line with automatic level detection and timestamp formatting.
     */
    fun log(rawMessage: String) {
        val level = parseLogLevel(rawMessage)
        val timestamp = dateFormat.format(Date())
        val formatted = "[$timestamp] [${level.name}] $rawMessage"
        logRaw(formatted)
    }

    /**
     * Logs a structured entry.
     */
    fun log(level: LogLevel, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val formatted = "[$timestamp] [${level.name}] [$tag] $message"
        logRaw(formatted)
    }

    private fun logRaw(text: String) {
        synchronized(lock) {
            val file = currentLogFile ?: return
            runCatching {
                FileOutputStream(file, true).bufferedWriter().use { writer ->
                    writer.write(text)
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }

    /**
     * Returns all available session log files, sorted newest first.
     */
    fun getLogFiles(): List<File> {
        synchronized(lock) {
            val dir = logDir ?: return emptyList()
            if (!dir.exists()) return emptyList()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return emptyList()
            return files.sortedByDescending { it.lastModified() }
        }
    }

    /**
     * Returns total size in bytes of all stored log files.
     */
    fun getTotalLogSize(): Long {
        synchronized(lock) {
            return getLogFiles().sumOf { it.length() }
        }
    }

    /**
     * Deletes all historical log files and starts a clean current session file.
     */
    fun clearAllLogs(): Boolean {
        synchronized(lock) {
            val dir = logDir ?: return false
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: emptyArray()
            var allDeleted = true
            for (f in files) {
                if (!f.delete()) allDeleted = false
            }
            val sessionName = "dtbo_log_${fileTimestampFormat.format(Date())}.log"
            currentLogFile = File(dir, sessionName).apply {
                createNewFile()
            }
            return allDeleted
        }
    }

    /**
     * Exports all log files sequentially into the provided output stream.
     */
    fun exportLogs(outputStream: OutputStream) {
        synchronized(lock) {
            val files = getLogFiles()
            outputStream.bufferedWriter().use { writer ->
                writer.write("====================================================\n")
                writer.write(" DTBO Refresh Overclocker Diagnostic Export Log\n")
                writer.write(" Exported At: ${dateFormat.format(Date())}\n")
                writer.write(" Log Sessions: ${files.size}\n")
                writer.write("====================================================\n\n")

                if (files.isEmpty()) {
                    writer.write("[EMPTY] 无可用日志记录。\n")
                    writer.flush()
                    return
                }

                // Write from oldest to newest for chronological reading
                for (file in files.reversed()) {
                    writer.write("-------------------- SESSION: ${file.name} --------------------\n")
                    runCatching {
                        file.bufferedReader().use { reader ->
                            reader.copyTo(writer)
                        }
                    }.onFailure {
                        writer.write("[ERROR] 无法读取日志文件: ${file.name}\n")
                    }
                    writer.write("\n\n")
                }
                writer.flush()
            }
        }
    }

    private fun rotateLogs(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return
        if (files.size >= MAX_LOG_FILES) {
            val sorted = files.sortedBy { it.lastModified() }
            val toDeleteCount = files.size - MAX_LOG_FILES + 1
            for (i in 0 until toDeleteCount) {
                sorted.getOrNull(i)?.delete()
            }
        }
    }

    private fun parseLogLevel(line: String): LogLevel {
        val upper = line.uppercase()
        return when {
            upper.contains("[CRITICAL]") || upper.contains("CRITICAL") -> LogLevel.CRITICAL
            upper.contains("[ERROR]") || upper.contains("ERROR") || upper.contains("失败") -> LogLevel.ERROR
            upper.contains("[WARN]") || upper.contains("WARNING") || upper.contains("警告") -> LogLevel.WARN
            upper.contains("[DEBUG]") || upper.contains("DEBUG") -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }
    }
}
