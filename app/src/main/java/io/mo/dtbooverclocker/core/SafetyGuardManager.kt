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
import io.mo.dtbooverclocker.model.SlotInfo
import io.mo.dtbooverclocker.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        logSink("[IMAGE][ROOT_DUMP] source=${slot.blockDevice}, file=${output.absolutePath}, " +
            "input_size=${output.length()}, input_sha256=${HashUtils.sha256(output)}")
        logSink("[OK] 已提取当前活跃槽位镜像：${output.absolutePath}")
        output
    }

    suspend fun flashPatchedImage(
        report: PatchReport,
        slot: SlotInfo
    ): FlashResult = guardedFlash(report, slot) {
        logSink("[SAFE] 第 3/3 层：仅写入当前目标 ${slot.blockDevice}；不会触碰另一槽位")
        require(ddWrite(report.outputImage, slot.blockDevice)) { "物理写入失败；原始备份与 Rescue Zip 已保留" }
    }

    /**
     * 打包成 KernelSU / Magisk / APatch 模块并用当前 Root 管理器安装。
     * 模块 customize.sh 负责写入活跃槽位；本函数前后仍执行与直刷相同的备份、救砖包与回读校验。
     */
    suspend fun installPatchedModule(
        report: PatchReport,
        slot: SlotInfo,
        summary: String
    ): FlashResult = guardedFlash(report, slot) {
        val manager = detectModuleManager()
            ?: error("未检测到 KernelSU / Magisk / APatch，无法以模块方式刷入")
        val zip = generateModuleZip(report.outputImage, summary)
        logSink("[SAFE] 第 3/3 层：通过 ${manager.displayName} 安装模块，由模块写入 ${slot.blockDevice}")
        val result = rootDetector.runRoot(manager.installCommand(zip.absolutePath), timeoutMs = 180_000)
        (result.stdout + "\n" + result.stderr).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { logSink("[MODULE] $it") }
        require(result.isSuccess) { "${manager.displayName} 模块安装失败 (exit=${result.exitCode})；原始备份与 Rescue Zip 已保留" }
    }

    suspend fun generateModuleZip(
        patchedImage: File,
        summary: String,
        outputName: String = "DTBO_Module.zip"
    ): File = withContext(Dispatchers.IO) {
        val output = FlashPackageBuilder.writeModuleZip(
            output = File(context.filesDir, "module_bundles/$outputName"),
            patchedImage = patchedImage,
            summary = summary,
            versionCode = (System.currentTimeMillis() / 1000L).toInt()
        )
        logSink("[OK] 已生成 KernelSU / Magisk 模块：${output.absolutePath}")
        output
    }

    enum class ModuleManager(val displayName: String) {
        KERNELSU("KernelSU"),
        APATCH("APatch"),
        MAGISK("Magisk");

        fun installCommand(zip: String): List<String> = when (this) {
            KERNELSU -> listOf("/data/adb/ksud", "module", "install", zip)
            APATCH -> listOf("/data/adb/apd", "module", "install", zip)
            MAGISK -> listOf("magisk", "--install-module", zip)
        }
    }

    suspend fun detectModuleManager(): ModuleManager? {
        val probe = rootDetector.runRoot(
            listOf(
                "sh", "-c",
                "if [ -x /data/adb/ksud ]; then echo KERNELSU; " +
                    "elif [ -x /data/adb/apd ]; then echo APATCH; " +
                    "elif command -v magisk >/dev/null 2>&1; then echo MAGISK; fi"
            ),
            timeoutMs = 10_000
        )
        val name = probe.stdout.trim().lineSequence().lastOrNull().orEmpty()
        return ModuleManager.entries.firstOrNull { it.name == name }
    }

    private suspend fun guardedFlash(
        report: PatchReport,
        slot: SlotInfo,
        write: suspend () -> Unit
    ): FlashResult = withContext(Dispatchers.IO) {
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
        write()

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
        val output = FlashPackageBuilder.writeRecoveryZip(
            output = File(context.filesDir, "recovery_flash_bundles/$outputName"),
            image = patchedImage,
            imageEntryName = FlashPackageBuilder.PATCHED_IMAGE,
            partition = slot.fastbootPartition,
            title = "Flashing patched DTBO",
            readme = listOf("DTBO Refresh Overclocker patched Recovery package")
        )
        logSink("[OK] 已生成单槽位 Recovery 刷机 Zip：${output.absolutePath}")
        output
    }

    suspend fun generateRecoveryZip(
        originalImage: File,
        slot: SlotInfo,
        outputName: String = "DTBO_Recovery_Rescue.zip"
    ): File = withContext(Dispatchers.IO) {
        validateBlockPath(slot.blockDevice)
        val output = FlashPackageBuilder.writeRecoveryZip(
            output = File(context.filesDir, "rescue/$outputName"),
            image = originalImage,
            imageEntryName = FlashPackageBuilder.BACKUP_IMAGE,
            partition = slot.fastbootPartition,
            title = "Restoring original DTBO",
            readme = listOf(
                "DTBO Refresh Overclocker rescue package",
                "Never modify it to flash both A/B slots at once."
            )
        )
        logSink("[OK] 已生成 Recovery Rescue Zip：${output.absolutePath}")
        output
    }

    suspend fun generateFastbootBundle(
        patchedImage: File,
        slot: SlotInfo,
        originalImage: File? = null,
        outputName: String = "DTBO_Fastboot_Bundle.zip"
    ): File = withContext(Dispatchers.IO) {
        val output = FlashPackageBuilder.writeFastbootBundle(
            output = File(context.filesDir, "fastboot_bundles/$outputName"),
            patchedImage = patchedImage,
            originalImage = originalImage,
            partition = slot.fastbootPartition,
            oppositeSlot = slot.oppositeFastbootSlot
        )
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
}
