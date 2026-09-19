package io.mo.dtbooverclocker.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.mo.dtbooverclocker.model.BackupType
import io.mo.dtbooverclocker.model.FlashResult
import io.mo.dtbooverclocker.model.PatchReport
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.SlotInfo
import io.mo.dtbooverclocker.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

class SafetyGuardManager(
    private val context: Context,
    private val rootDetector: RootDetector,
    private val logSink: (String) -> Unit = {},
    val backupManager: BackupManager = BackupManager(context, rootDetector, logSink)
) {
    suspend fun extractActiveImage(slot: SlotInfo): File = withContext(Dispatchers.IO) {
        validateBlockPath(slot.blockDevice)
        requireRoot()

        val dir = File(context.cacheDir, "root_extract").apply { mkdirs() }
        val output = File(dir, "dtbo_${slot.suffix.ifBlank { "single" }}_${System.currentTimeMillis()}.img")
        val result = rootDetector.runRoot(
            listOf("dd", "if=${slot.blockDevice}", "of=${output.absolutePath}", "bs=4M")
        )
        require(result.isSuccess && output.isFile && output.length() >= 32) {
            "从 ${slot.blockDevice} 提取 DTBO 失败：${result.stderr.ifBlank { result.stdout }.takeLast(1500)}"
        }
        logSink("[OK] 已提取当前活跃槽位镜像：${output.absolutePath}")
        output
    }

    suspend fun flashPatchedImage(
        report: PatchReport,
        slot: SlotInfo
    ): FlashResult = withContext(Dispatchers.IO) {
        require(report.strategy != PatchStrategy.FRAMERATE_ONLY) {
            "仅 Framerate 策略被禁止直接刷写；请导出镜像后自行离线验证，或选择完整时序策略。"
        }
        validateBlockPath(slot.blockDevice)
        require(report.outputImage.isFile && report.outputImage.length() >= 32) {
            "修补镜像不存在或无效"
        }
        requireRoot()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val slotName = slot.suffix.removePrefix("_").ifBlank { "single" }
        val backupName = "dtbo_backup_auto_${slotName}_${timestamp}.img"

        val cacheBackup = File(context.cacheDir, "flash_guard/$backupName").apply {
            parentFile?.mkdirs()
        }
        logSink("[SAFE] 第 1/3 层：读取物理分区并生成原厂备份")
        val readBackup = rootDetector.runRoot(
            listOf("dd", "if=${slot.blockDevice}", "of=${cacheBackup.absolutePath}", "bs=4M")
        )
        require(readBackup.isSuccess && cacheBackup.length() >= 32) {
            "强制备份失败，已阻止物理写入"
        }
        require(report.outputImage.length() <= cacheBackup.length()) {
            "修补镜像 (${report.outputImage.length()}) 大于目标分区备份 (${cacheBackup.length()})，已阻止刷写"
        }

        val backupRecord = backupManager.createBackupFromFile(
            source = cacheBackup,
            slot = slot,
            type = BackupType.AUTO,
            description = "刷入目标 ${report.targetHz}Hz (${report.strategy.displayName}) 前自动备份"
        )
        val persistentBackup = File(backupRecord.filePath)
        val backupHash = backupRecord.recordedSha256.ifBlank { HashUtils.sha256(persistentBackup) }
        val backupMd5 = backupRecord.recordedMd5

        val externalBackup = persistExternalArtifact(
            source = persistentBackup,
            displayName = persistentBackup.name,
            mimeType = "application/octet-stream",
            subDirectory = "backups"
        )
        require(externalBackup.verifiedSha256 == backupHash) {
            "外部备份 SHA-256 校验失败，已阻止刷写"
        }
        logSink("[SAFE] 原厂备份 SHA-256=$backupHash, MD5=$backupMd5")

        logSink("[SAFE] 第 2/3 层：预生成 Recovery 救砖包")
        val rescueZip = generateRecoveryZip(
            originalImage = persistentBackup,
            slot = slot,
            outputName = "DTBO_Rescue_${slotName}_${timestamp}.zip"
        )
        val rescueExternal = persistExternalArtifact(
            source = rescueZip,
            displayName = rescueZip.name,
            mimeType = "application/zip",
            subDirectory = "rescue"
        )
        require(rescueExternal.size > 0L) { "救砖 Zip 外部落盘失败，已阻止刷写" }

        val patchedHash = HashUtils.sha256(report.outputImage)
        logSink("[SAFE] 第 3/3 层：仅写入当前目标 ${slot.blockDevice}；不会触碰另一槽位")

        val write = ddWrite(report.outputImage, slot.blockDevice)
        require(write) { "物理写入失败；原始备份与 Rescue Zip 已保留" }

        val verifyFile = File(context.cacheDir, "flash_guard/readback_${System.currentTimeMillis()}.img")
        val blockCount = ceil(report.outputImage.length() / 4096.0).toLong().coerceAtLeast(1L)
        val readBack = rootDetector.runRoot(
            listOf(
                "dd",
                "if=${slot.blockDevice}",
                "of=${verifyFile.absolutePath}",
                "bs=4096",
                "count=$blockCount"
            )
        )

        val readBackHash = if (readBack.isSuccess && verifyFile.length() >= report.outputImage.length()) {
            verifyFile.inputStream().use { HashUtils.sha256(it, report.outputImage.length()) }
        } else {
            ""
        }
        val verified = readBackHash == patchedHash

        if (!verified) {
            logSink("[CRITICAL] 写后回读校验失败，立即尝试恢复原始备份")
            val rollbackOk = ddWrite(persistentBackup, slot.blockDevice)
            if (!rollbackOk) {
                throw IllegalStateException(
                    "写后校验失败，自动回滚也失败。请不要重启，立即导出备份并使用 Recovery/Fastboot 恢复。"
                )
            }
            throw IllegalStateException("写后校验失败，但已自动恢复原始 DTBO；本次刷写视为失败。")
        }

        logSink("[OK] 物理写入完成，前 ${report.outputImage.length()} 字节 SHA-256 回读一致")

        FlashResult(
            backupFile = persistentBackup,
            backupSha256 = backupHash,
            backupExternalUri = externalBackup.uri?.toString(),
            rescueZip = rescueZip,
            rescueExternalUri = rescueExternal.uri?.toString(),
            flashedPartition = slot.blockDevice,
            patchedSha256 = patchedHash,
            readBackVerified = true,
            rollbackCommands = buildRollbackCommands(slot, backupName)
        )
    }

    suspend fun generatePatchedRecoveryZip(
        patchedImage: File,
        slot: SlotInfo,
        outputName: String = "DTBO_Patched_Recovery.zip"
    ): File = withContext(Dispatchers.IO) {
        validateBlockPath(slot.blockDevice)
        require(patchedImage.isFile && patchedImage.length() >= 32) { "修补 DTBO 镜像无效" }

        val outputDir = File(context.filesDir, "recovery_flash_bundles").apply { mkdirs() }
        val output = File(outputDir, outputName)
        val patchedHash = HashUtils.sha256(patchedImage)

        val updater = buildString {
            appendLine("#!/sbin/sh")
            appendLine("OUTFD=\"\$2\"")
            appendLine("ZIPFILE=\"\$3\"")
            appendLine("TARGET=\"${slot.blockDevice}\"")
            appendLine("TMP=\"/tmp/dtbo_patched.img\"")
            appendLine("ui_print() { echo \"ui_print \$1\" > /proc/self/fd/\$OUTFD; echo \"ui_print\" > /proc/self/fd/\$OUTFD; }")
            appendLine("ui_print \"DTBO Refresh Overclocker\"")
            appendLine("ui_print \"Flashing ONLY: \$TARGET\"")
            appendLine("unzip -p \"\$ZIPFILE\" dtbo_patched.img > \"\$TMP\" || exit 20")
            appendLine("dd if=\"\$TMP\" of=\"\$TARGET\" bs=4M conv=fsync 2>/dev/null || dd if=\"\$TMP\" of=\"\$TARGET\" bs=4M || exit 21")
            appendLine("sync")
            appendLine("ui_print \"Flash complete. Image SHA-256: $patchedHash\"")
            appendLine("exit 0")
        }

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putTextEntry("META-INF/com/google/android/update-binary", updater)
            zip.putTextEntry(
                "META-INF/com/google/android/updater-script",
                "ui_print(\"DTBO patched package; update-binary handles flashing.\");\n"
            )
            zip.putTextEntry(
                "README.txt",
                buildString {
                    appendLine("DTBO Refresh Overclocker patched Recovery package")
                    appendLine("Target block: ${slot.blockDevice}")
                    appendLine("Patched SHA-256: $patchedHash")
                    appendLine("This package writes ONLY this one DTBO partition.")
                    appendLine("Recovery compatibility varies; verify your recovery supports legacy update-binary ZIPs.")
                }
            )
            zip.putNextEntry(ZipEntry("dtbo_patched.img"))
            patchedImage.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }

        require(output.isFile && output.length() > 0L) { "Recovery 刷机 Zip 生成失败" }
        logSink("[OK] 已生成单槽位 Recovery 刷机 Zip：${output.absolutePath}")
        output
    }

    suspend fun generateRecoveryZip(
        originalImage: File,
        slot: SlotInfo,
        outputName: String = "DTBO_Recovery_Rescue.zip"
    ): File = withContext(Dispatchers.IO) {
        validateBlockPath(slot.blockDevice)
        require(originalImage.isFile && originalImage.length() >= 32) { "原始 DTBO 备份无效" }

        val outputDir = File(context.filesDir, "rescue").apply { mkdirs() }
        val output = File(outputDir, outputName)
        val backupHash = HashUtils.sha256(originalImage)

        val updater = buildString {
            appendLine("#!/sbin/sh")
            appendLine("OUTFD=\"\$2\"")
            appendLine("ZIPFILE=\"\$3\"")
            appendLine("TARGET=\"${slot.blockDevice}\"")
            appendLine("TMP=\"/tmp/dtbo_backup.img\"")
            appendLine("ui_print() { echo \"ui_print \$1\" > /proc/self/fd/\$OUTFD; echo \"ui_print\" > /proc/self/fd/\$OUTFD; }")
            appendLine("ui_print \"DTBO Refresh Overclocker Rescue\"")
            appendLine("ui_print \"Target: \$TARGET\"")
            appendLine("unzip -p \"\$ZIPFILE\" dtbo_backup.img > \"\$TMP\" || exit 10")
            appendLine("dd if=\"\$TMP\" of=\"\$TARGET\" bs=4M conv=fsync 2>/dev/null || dd if=\"\$TMP\" of=\"\$TARGET\" bs=4M || exit 11")
            appendLine("sync")
            appendLine("ui_print \"Restore complete. SHA-256 expected: $backupHash\"")
            appendLine("exit 0")
        }

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putTextEntry("META-INF/com/google/android/update-binary", updater)
            zip.putTextEntry(
                "META-INF/com/google/android/updater-script",
                "ui_print(\"DTBO Rescue package; update-binary handles flashing.\");\n"
            )
            zip.putTextEntry(
                "RESCUE_README.txt",
                buildString {
                    appendLine("DTBO Refresh Overclocker rescue package")
                    appendLine("Target block: ${slot.blockDevice}")
                    appendLine("Backup SHA-256: $backupHash")
                    appendLine("This package intentionally writes only one DTBO partition.")
                    appendLine("Never modify it to flash both A/B slots at once.")
                }
            )
            zip.putNextEntry(ZipEntry("dtbo_backup.img"))
            originalImage.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }

        require(output.isFile && output.length() > 0L) { "Recovery Zip 生成失败" }
        logSink("[OK] 已生成 Recovery Rescue Zip：${output.absolutePath}")
        output
    }

    suspend fun generateFastbootBundle(
        patchedImage: File,
        slot: SlotInfo,
        originalImage: File? = null,
        outputName: String = "DTBO_Fastboot_Bundle.zip"
    ): File = withContext(Dispatchers.IO) {
        require(patchedImage.isFile && patchedImage.length() >= 32) { "修补镜像无效" }
        originalImage?.let { require(it.isFile) { "原始镜像不存在" } }

        val outputDir = File(context.filesDir, "fastboot_bundles").apply { mkdirs() }
        val output = File(outputDir, outputName)
        val partition = slot.fastbootPartition

        val flashBat = """@echo off
            |echo DTBO Refresh Overclocker - flashing ONLY $partition
            |fastboot devices
            |fastboot flash $partition dtbo_patched.img
            |if errorlevel 1 goto fail
            |echo Flash complete. Reboot manually after checking the output.
            |pause
            |exit /b 0
            |:fail
            |echo Flash failed. DO NOT flash the opposite slot.
            |pause
            |exit /b 1
        """.trimMargin()

        val flashSh = """#!/bin/sh
            |set -eu
            |echo "DTBO Refresh Overclocker - flashing ONLY $partition"
            |fastboot devices
            |fastboot flash "$partition" dtbo_patched.img
            |echo "Flash complete. Reboot manually after checking the output."
        """.trimMargin()

        val rollbackLine = "fastboot flash $partition dtbo_backup.img"
        val opposite = slot.oppositeFastbootSlot?.let { "fastboot --set-active=$it" }

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putTextEntry("flash_patched.bat", flashBat)
            zip.putTextEntry("flash_patched.sh", flashSh)
            zip.putTextEntry(
                "README.txt",
                buildString {
                    appendLine("Target partition: $partition")
                    appendLine("This bundle NEVER flashes both slots.")
                    appendLine("Rollback: $rollbackLine")
                    opposite?.let { appendLine("Emergency alternate-slot boot: $it") }
                }
            )

            zip.putNextEntry(ZipEntry("dtbo_patched.img"))
            patchedImage.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            if (originalImage != null) {
                zip.putNextEntry(ZipEntry("dtbo_backup.img"))
                originalImage.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putTextEntry("rollback.bat", "@echo off\n$rollbackLine\npause\n")
                zip.putTextEntry("rollback.sh", "#!/bin/sh\nset -eu\n$rollbackLine\n")
            }
        }

        logSink("[OK] 已生成 Fastboot 一键包：${output.absolutePath}")
        output
    }

    private suspend fun requireRoot() {
        val state = rootDetector.requestRoot()
        require(state.granted) { "需要 Root 授权：${state.detail}" }
    }

    private fun validateBlockPath(path: String) {
        val allowed = Regex("^/dev/block/(?:by-name|bootdevice/by-name)/dtbo(?:_[ab])?$")
        require(allowed.matches(path)) { "拒绝访问非 DTBO 白名单块设备：$path" }
    }

    private fun persistInternalBackup(source: File, name: String): File {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val target = File(dir, name)
        source.inputStream().use { input ->
            FileOutputStream(target).use { out ->
                input.copyTo(out)
                out.fd.sync()
            }
        }
        require(target.length() == source.length()) { "内部持久化备份长度不一致" }
        return target
    }

    private suspend fun ddWrite(source: File, targetBlock: String): Boolean {
        val withFsync = rootDetector.runRoot(
            listOf("dd", "if=${source.absolutePath}", "of=$targetBlock", "bs=4M", "conv=fsync")
        )
        if (withFsync.isSuccess) {
            rootDetector.runRoot(listOf("sync"), timeoutMs = 10_000)
            return true
        }

        logSink("[WARN] 当前 dd 不支持 conv=fsync，改用 dd + sync")
        val fallback = rootDetector.runRoot(
            listOf("dd", "if=${source.absolutePath}", "of=$targetBlock", "bs=4M")
        )
        if (!fallback.isSuccess) return false
        return rootDetector.runRoot(listOf("sync"), timeoutMs = 10_000).isSuccess
    }

    private data class ExternalArtifact(
        val uri: Uri?,
        val file: File?,
        val size: Long,
        val verifiedSha256: String?
    )

    private fun persistExternalArtifact(
        source: File,
        displayName: String,
        mimeType: String,
        subDirectory: String
    ): ExternalArtifact {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/DTBORefreshOverclocker/$subDirectory"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Downloads 创建 $displayName")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } ?: error("无法写入 Downloads URI")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                val hash = resolver.openInputStream(uri)?.use { input -> HashUtils.sha256(input) }
                    ?: error("无法重新读取 Downloads 备份进行 SHA-256 校验")
                val statSize = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                val size = if (statSize > 0L) statSize else source.length()
                ExternalArtifact(uri, null, size, hash)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("外部存储不可用")
            val dir = File(base, "DTBORefreshOverclocker/$subDirectory").apply { mkdirs() }
            val target = File(dir, displayName)
            source.copyTo(target, overwrite = true)
            ExternalArtifact(
                uri = Uri.fromFile(target),
                file = target,
                size = target.length(),
                verifiedSha256 = HashUtils.sha256(target)
            )
        }
    }

    private fun buildRollbackCommands(slot: SlotInfo, backupName: String): List<String> {
        val commands = mutableListOf<String>()
        slot.oppositeFastbootSlot?.let { opposite ->
            commands += "fastboot --set-active=$opposite"
        }
        commands += "fastboot flash ${slot.fastbootPartition} $backupName"
        return commands
    }

    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
