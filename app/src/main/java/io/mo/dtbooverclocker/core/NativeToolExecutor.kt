package io.mo.dtbooverclocker.core

import android.content.Context
import io.mo.dtbooverclocker.model.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeToolExecutor(
    context: Context,
    private val logSink: (String) -> Unit = {}
) {
    private val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)

    /**
     * Only one native executable is required now. DTBO table parsing/rebuild is
     * implemented in Kotlin by [DtboImageCodec].
     */
    val dtcBinary: File = File(nativeLibraryDir, "libdtc.so")

    fun validateToolchain(): Result<Unit> {
        return if (dtcBinary.exists() && dtcBinary.isFile) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "缺少 ARM64 DTC 工具：${dtcBinary.absolutePath}。" +
                        "请将 Android arm64-v8a 可执行 ELF 按 libdtc.so 命名后放入 " +
                        "app/src/main/jniLibs/arm64-v8a/。"
                )
            )
        }
    }

    suspend fun runDtc(
        args: List<String>,
        workingDir: File? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult {
        validateToolchain().getOrThrow()
        return run(listOf(dtcBinary.absolutePath) + args, workingDir, timeoutMs)
    }

    suspend fun run(
        command: List<String>,
        workingDir: File? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        extraEnvironment: Map<String, String> = emptyMap()
    ): CommandResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "command 不能为空" }

        val startedAt = System.nanoTime()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readersDone = CountDownLatch(2)
        val readerPool = Executors.newFixedThreadPool(2)

        logSink("$ ${command.joinToString(" ") { displayQuote(it) }}")

        val processBuilder = ProcessBuilder(command)
        workingDir?.let(processBuilder::directory)
        processBuilder.redirectErrorStream(false)
        processBuilder.environment().apply {
            putAll(extraEnvironment)
            put("TMPDIR", workingDir?.absolutePath ?: get("TMPDIR").orEmpty())
        }

        val process = processBuilder.start()

        readerPool.execute {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stdout.appendLine(line)
                        logSink("[OUT] $line")
                    }
                }
            } finally {
                readersDone.countDown()
            }
        }

        readerPool.execute {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stderr.appendLine(line)
                        logSink("[ERR] $line")
                    }
                }
            } finally {
                readersDone.countDown()
            }
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            logSink("[WARN] 命令超时，正在终止进程。")
            process.destroy()
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        }

        readersDone.await(2, TimeUnit.SECONDS)
        readerPool.shutdownNow()

        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val exitCode = if (finished) process.exitValue() else -1

        CommandResult(
            command = command,
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = !finished,
            durationMs = durationMs
        ).also { result ->
            logSink(
                if (result.isSuccess) {
                    "[OK] exit=${result.exitCode}, ${result.durationMs} ms"
                } else {
                    "[FAIL] exit=${result.exitCode}, timeout=${result.timedOut}, ${result.durationMs} ms"
                }
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L

        fun shellQuote(value: String): String {
            if (value.isEmpty()) return "''"
            return "'${value.replace("'", "'\\''")}'"
        }

        private fun displayQuote(value: String): String {
            return if (value.any(Char::isWhitespace)) "\"$value\"" else value
        }
    }
}
