package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.CommandResult
import io.mo.dtbooverclocker.model.RootState
import java.io.File

class RootDetector(
    private val executor: NativeToolExecutor
) {
    suspend fun probe(checkAuth: Boolean = false): RootState {
        val knownLocations = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su"
        )

        val foundByPath = knownLocations.any { File(it).exists() }
        val whichResult = executor.run(
            command = listOf("/system/bin/sh", "-c", "command -v su 2>/dev/null"),
            timeoutMs = 3000
        )
        val present = foundByPath || whichResult.isSuccess && whichResult.stdout.isNotBlank()

        if (!present) {
            return RootState(
                suPresent = false,
                granted = false,
                detail = "未检测到 su"
            )
        }

        if (checkAuth) {
            val granted = quickVerifyRoot(2500)
            if (granted) {
                return RootState(
                    suPresent = true,
                    granted = true,
                    detail = "Root 已授权"
                )
            }
        }

        return RootState(
            suPresent = true,
            granted = false,
            detail = "检测到 su，尚未请求授权"
        )
    }

    suspend fun quickVerifyRoot(timeoutMs: Long = 3000): Boolean {
        val result = executor.run(
            command = listOf("su", "-c", "id -u"),
            timeoutMs = timeoutMs
        )
        return result.isSuccess && result.stdout.trim().lineSequence().lastOrNull() == "0"
    }

    suspend fun requestRoot(): RootState {
        val probe = probe()
        if (!probe.suPresent) return probe

        val result = executor.run(
            command = listOf("su", "-c", "id -u"),
            timeoutMs = 15_000
        )
        val granted = result.isSuccess && result.stdout.trim().lineSequence().lastOrNull() == "0"

        return RootState(
            suPresent = true,
            granted = granted,
            detail = if (granted) "Root 已授权" else "Root 未授权或 su 执行失败"
        )
    }

    suspend fun runRoot(
        args: List<String>,
        timeoutMs: Long = NativeToolExecutor.DEFAULT_TIMEOUT_MS
    ): CommandResult {
        require(args.isNotEmpty()) { "Root 命令不能为空" }

        val command = args.joinToString(" ") { NativeToolExecutor.shellQuote(it) }
        return executor.run(
            command = listOf("su", "-c", command),
            timeoutMs = timeoutMs
        )
    }
}
