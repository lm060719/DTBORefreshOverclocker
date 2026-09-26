package io.mo.dtbooverclocker.core.devicetree

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Exception-safe batch commit. Callers serialize access to the workspace.
 * This is not a crash-recovery journal: process/power loss across multiple files is not atomic.
 */
object DtsFileTransaction {
    fun <T> commit(replacements: Map<File, String>, afterWrite: () -> T): T {
        val backups = linkedMapOf<File, File>()
        val staged = linkedMapOf<File, File>()
        val attempted = mutableListOf<File>()
        val retainedBackups = mutableSetOf<File>()
        try {
            // Prepare every file before replacing any live DTS.
            replacements.forEach { (file, text) ->
                require(file.isFile) { "DTS 文件不存在：${file.name}" }
                val backup = File.createTempFile(".dts-backup-", ".tmp", file.parentFile)
                backups[file] = backup
                file.copyTo(backup, overwrite = true)
                val pending = File.createTempFile(".dts-pending-", ".tmp", file.parentFile)
                staged[file] = pending
                pending.writeText(text)
            }
            staged.forEach { (file, pending) ->
                attempted += file
                replace(pending, file)
            }
            return afterWrite()
        } catch (failure: Throwable) {
            attempted.asReversed().forEach { file ->
                val backup = backups.getValue(file)
                try {
                    replace(backup, file)
                } catch (rollbackFailure: Throwable) {
                    retainedBackups += backup
                    failure.addSuppressed(IllegalStateException(
                        "恢复 ${file.name} 失败，原始文件保留在 ${backup.absolutePath}", rollbackFailure
                    ))
                }
            }
            throw failure
        } finally {
            (staged.values + backups.values).filterNot { it in retainedBackups }.forEach { it.delete() }
        }
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
