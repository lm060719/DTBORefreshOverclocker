package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.SlotInfo

class SlotDetector(
    private val executor: NativeToolExecutor
) {
    suspend fun detect(): SlotInfo {
        var suffix = getProp("ro.boot.slot_suffix").trim()

        if (suffix.isBlank()) {
            val slot = getProp("ro.boot.slot").trim().lowercase()
            suffix = when (slot) {
                "a" -> "_a"
                "b" -> "_b"
                else -> ""
            }
        }

        if (suffix !in setOf("", "_a", "_b")) {
            throw IllegalStateException("不支持的 slot_suffix：$suffix")
        }

        val label = when (suffix) {
            "_a" -> "Slot A"
            "_b" -> "Slot B"
            else -> "Single Slot / Unknown"
        }
        val opposite = when (suffix) {
            "_a" -> "_b"
            "_b" -> "_a"
            else -> null
        }

        return SlotInfo(
            suffix = suffix,
            label = label,
            blockDevice = "/dev/block/by-name/dtbo$suffix",
            oppositeSuffix = opposite
        )
    }

    private suspend fun getProp(name: String): String {
        val result = executor.run(
            command = listOf("/system/bin/getprop", name),
            timeoutMs = 3000
        )
        return if (result.isSuccess) result.stdout.trim() else ""
    }
}
