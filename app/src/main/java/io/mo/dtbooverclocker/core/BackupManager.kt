package io.mo.dtbooverclocker.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.mo.dtbooverclocker.model.BackupRecord
import io.mo.dtbooverclocker.model.BackupType
import io.mo.dtbooverclocker.model.BackupVerificationState
import io.mo.dtbooverclocker.model.BackupVerificationStatus
import io.mo.dtbooverclocker.model.SlotInfo
import io.mo.dtbooverclocker.util.AppLogger
import io.mo.dtbooverclocker.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

class BackupManager(
    private val context: Context,
    private val rootDetector: RootDetector,
    private val logSink: (String) -> Unit = {}
) {
    private val lock = Any()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileTimeFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun getBackupDirectory(): File {
        return File(context.filesDir, "backups").apply { mkdirs() }
    }

    private fun getManifestFile(): File {
        return File(getBackupDirectory(), "backups_manifest.json")
    }

    /**
     * Creates a backup from the device's physical DTBO partition via Root.
     */
    suspend fun createBackupFromPartition(
        slot: SlotInfo,
        type: BackupType,
        description: String = ""
    ): BackupRecord = withContext(Dispatchers.IO) {
        validateBlockPath(slot.blockDevice)
        requireRoot()

        val timestamp = System.currentTimeMillis()
        val slotName = slot.suffix.removePrefix("_").ifBlank { "single" }
        val typeTag = if (type == BackupType.AUTO) "auto" else "manual"
        val fileName = "dtbo_backup_${typeTag}_${slotName}_${fileTimeFormatter.format(Date(timestamp))}.img"
        val backupDir = getBackupDirectory()
        val targetFile = File(backupDir, fileName)

        logSink("[BACKUP] 开始${type.displayName}：读取分区 ${slot.blockDevice} -> ${targetFile.name}")
        val dumpResult = rootDetector.runRoot(
            listOf("dd", "if=${slot.blockDevice}", "of=${targetFile.absolutePath}", "bs=4M")
        )
        require(dumpResult.isSuccess && targetFile.length() >= 32) {
            targetFile.delete()
            "从 ${slot.blockDevice} 读取镜像失败：${dumpResult.stderr.ifBlank { dumpResult.stdout }}"
        }

        val md5 = HashUtils.md5(targetFile)
        val sha256 = HashUtils.sha256(targetFile)
        logSink("[BACKUP] ${type.displayName}完成：大小=${targetFile.length()} 字节, MD5=$md5")

        val record = BackupRecord(
            id = UUID.randomUUID().toString(),
            fileName = fileName,
            filePath = targetFile.absolutePath,
            backupType = type,
            timestamp = timestamp,
            formattedTime = timeFormatter.format(Date(timestamp)),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            buildDisplay = "${Build.DISPLAY} (${Build.ID})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            slot = slot.label,
            blockDevice = slot.blockDevice,
            fileSizeBytes = targetFile.length(),
            recordedMd5 = md5,
            recordedSha256 = sha256,
            description = description.ifBlank { if (type == BackupType.AUTO) "自动备份" else "手动备份" }
        )

        registerBackupRecord(record)
        AppLogger.log("[BACKUP] [${type.name}] 新增备份记录: ${record.fileName}, MD5=${record.recordedMd5}")
        record
    }

    /**
     * Creates a backup from an existing file on disk (e.g. workspace input image or temporary dump).
     */
    suspend fun createBackupFromFile(
        source: File,
        slot: SlotInfo,
        type: BackupType,
        description: String = ""
    ): BackupRecord = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() >= 32) { "源备份文件无效" }

        val timestamp = System.currentTimeMillis()
        val slotName = slot.suffix.removePrefix("_").ifBlank { "single" }
        val typeTag = if (type == BackupType.AUTO) "auto" else "manual"
        val fileName = "dtbo_backup_${typeTag}_${slotName}_${fileTimeFormatter.format(Date(timestamp))}.img"
        val backupDir = getBackupDirectory()
        val targetFile = File(backupDir, fileName)

        source.inputStream().use { input ->
            FileOutputStream(targetFile).use { out ->
                input.copyTo(out)
                out.fd.sync()
            }
        }
        require(targetFile.length() == source.length()) { "备份文件写入不完整" }

        val md5 = HashUtils.md5(targetFile)
        val sha256 = HashUtils.sha256(targetFile)
        logSink("[BACKUP] 保存备份文件完成：${targetFile.name}, MD5=$md5")

        val record = BackupRecord(
            id = UUID.randomUUID().toString(),
            fileName = fileName,
            filePath = targetFile.absolutePath,
            backupType = type,
            timestamp = timestamp,
            formattedTime = timeFormatter.format(Date(timestamp)),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            buildDisplay = "${Build.DISPLAY} (${Build.ID})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            slot = slot.label,
            blockDevice = slot.blockDevice,
            fileSizeBytes = targetFile.length(),
            recordedMd5 = md5,
            recordedSha256 = sha256,
            description = description.ifBlank { if (type == BackupType.AUTO) "自动备份" else "手动备份" }
        )

        registerBackupRecord(record)
        AppLogger.log("[BACKUP] [${type.name}] 从文件创建备份: ${record.fileName}, MD5=${record.recordedMd5}")
        record
    }

    /**
     * Lists all backup records sorted chronologically (newest first).
     */
    fun getBackups(): List<BackupRecord> = synchronized(lock) {
        val records = loadManifest()
        val validRecords = mutableListOf<BackupRecord>()
        var modified = false

        for (record in records) {
            val file = File(record.filePath)
            if (file.exists()) {
                validRecords.add(record)
            } else {
                modified = true
                logSink("[BACKUP] 发现孤立元数据记录 (文件不存在): ${record.fileName}")
            }
        }

        if (modified) {
            saveManifest(validRecords)
        }

        validRecords.sortedByDescending { it.timestamp }
    }

    /**
     * Verifies MD5 checksum of the specified backup record.
     */
    suspend fun verifyMd5(record: BackupRecord): BackupVerificationState = withContext(Dispatchers.IO) {
        val file = File(record.filePath)
        if (!file.exists() || !file.isFile) {
            return@withContext BackupVerificationState(
                status = BackupVerificationStatus.FILE_MISSING,
                message = "备份文件已不存在于本地存储"
            )
        }

        val computed = runCatching { HashUtils.md5(file) }.getOrNull()
            ?: return@withContext BackupVerificationState(
                status = BackupVerificationStatus.MISMATCH,
                message = "MD5 计算失败"
            )

        val isMatched = computed.equals(record.recordedMd5, ignoreCase = true)
        if (isMatched) {
            BackupVerificationState(
                status = BackupVerificationStatus.MATCHED,
                computedMd5 = computed,
                message = "MD5 校验通过 (与记录完全一致)"
            )
        } else {
            BackupVerificationState(
                status = BackupVerificationStatus.MISMATCH,
                computedMd5 = computed,
                message = "MD5 不一致 (记录: ${record.recordedMd5.take(8)}..., 当前: ${computed.take(8)}...)"
            )
        }
    }

    /**
     * Exports backup file to user storage. If targetUri is provided, writes to it.
     * Otherwise exports to Public Downloads directory.
     */
    suspend fun exportBackup(
        record: BackupRecord,
        targetUri: Uri? = null
    ): String = withContext(Dispatchers.IO) {
        val sourceFile = File(record.filePath)
        require(sourceFile.exists() && sourceFile.isFile) { "备份文件不存在：${record.filePath}" }

        if (targetUri != null) {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("无法打开目标 URI 进行写入")
            logSink("[OK] 备份文件已导出至指定路径")
            return@withContext "已导出至指定位置"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, record.fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/DTBORefreshOverclocker/backups"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Downloads 中创建 ${record.fileName}")

            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: error("无法写入 Downloads")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                val targetPath = "Download/DTBORefreshOverclocker/backups/${record.fileName}"
                logSink("[OK] 备份镜像已成功导出至：$targetPath")
                targetPath
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DTBORefreshOverclocker/backups"
            ).apply { mkdirs() }
            val target = File(publicDir, record.fileName)
            sourceFile.copyTo(target, overwrite = true)
            val path = target.absolutePath
            logSink("[OK] 备份镜像已导出至：$path")
            path
        }
    }

    /**
     * Flashes a backup image directly back to the device partition (Rollback).
     */
    suspend fun flashBackup(
        record: BackupRecord,
        slot: SlotInfo
    ): Boolean = withContext(Dispatchers.IO) {
        validateBlockPath(slot.blockDevice)
        requireRoot()

        val file = File(record.filePath)
        require(file.exists() && file.isFile && file.length() >= 32) {
            "回滚失败：备份文件不存在或无效"
        }

        // Verify MD5 before physical flash
        val checkMd5 = HashUtils.md5(file)
        require(checkMd5.equals(record.recordedMd5, ignoreCase = true)) {
            "备份镜像 MD5 校验失败 (可能已被篡改或损坏)，已拒绝写入分区！"
        }

        logSink("[ROLLBACK] 正在准备将备份 ${record.fileName} 刷入 ${slot.blockDevice}…")
        AppLogger.log("[ROLLBACK] 开始回滚刷入备份: ${record.fileName} -> ${slot.blockDevice}")

        val withFsync = rootDetector.runRoot(
            listOf("dd", "if=${file.absolutePath}", "of=${slot.blockDevice}", "bs=4M", "conv=fsync")
        )
        val writeSuccess = if (withFsync.isSuccess) {
            rootDetector.runRoot(listOf("sync"), timeoutMs = 10_000)
            true
        } else {
            logSink("[WARN] 当前系统 dd 不支持 conv=fsync，改用 dd + sync")
            val fallback = rootDetector.runRoot(
                listOf("dd", "if=${file.absolutePath}", "of=${slot.blockDevice}", "bs=4M")
            )
            fallback.isSuccess && rootDetector.runRoot(listOf("sync"), timeoutMs = 10_000).isSuccess
        }

        require(writeSuccess) { "物理写入分区失败！" }

        // Readback verification
        val verifyFile = File(context.cacheDir, "rollback_verify_${System.currentTimeMillis()}.img").apply {
            parentFile?.mkdirs()
        }
        val blockCount = ceil(file.length() / 4096.0).toLong().coerceAtLeast(1L)
        val readBack = rootDetector.runRoot(
            listOf(
                "dd",
                "if=${slot.blockDevice}",
                "of=${verifyFile.absolutePath}",
                "bs=4096",
                "count=$blockCount"
            )
        )

        val verified = if (readBack.isSuccess && verifyFile.length() >= file.length()) {
            val readHash = verifyFile.inputStream().use { HashUtils.md5(it, file.length()) }
            readHash.equals(record.recordedMd5, ignoreCase = true)
        } else {
            false
        }
        verifyFile.delete()

        require(verified) { "写后回读 MD5 校验不一致，回滚写入可能未完全生效" }

        logSink("[OK] 镜像回滚刷入成功！前 ${file.length()} 字节 MD5 回读校验一致。")
        AppLogger.log("[ROLLBACK] 回滚完成并通过写后回读校验: ${record.fileName}")
        true
    }

    /**
     * Deletes a backup record and its corresponding file.
     */
    fun deleteBackup(recordId: String): Boolean = synchronized(lock) {
        val records = loadManifest()
        val target = records.find { it.id == recordId } ?: return false
        val file = File(target.filePath)
        if (file.exists()) {
            file.delete()
        }
        val updated = records.filterNot { it.id == recordId }
        saveManifest(updated)
        logSink("[BACKUP] 已删除备份：${target.fileName}")
        AppLogger.log("[BACKUP] 删除备份记录: ${target.fileName}")
        true
    }

    private fun registerBackupRecord(record: BackupRecord) = synchronized(lock) {
        val records = loadManifest().filterNot { it.id == record.id }.toMutableList()
        records.add(0, record)
        saveManifest(records)
    }

    private fun loadManifest(): List<BackupRecord> {
        val manifestFile = getManifestFile()
        if (!manifestFile.exists()) return emptyList()
        return runCatching {
            val text = manifestFile.readText()
            parseBackupRecordsJson(text)
        }.getOrDefault(emptyList())
    }

    private fun saveManifest(records: List<BackupRecord>) {
        val manifestFile = getManifestFile()
        val tempFile = File(manifestFile.parentFile, "${manifestFile.name}.tmp")
        val json = serializeBackupRecordsJson(records)
        tempFile.writeText(json)
        if (manifestFile.exists()) manifestFile.delete()
        tempFile.renameTo(manifestFile)
    }

    private suspend fun requireRoot() {
        val state = rootDetector.requestRoot()
        require(state.granted) { "执行此操作需要 Root 权限：${state.detail}" }
    }

    private fun validateBlockPath(path: String) {
        val allowed = Regex("^/dev/block/(?:by-name|bootdevice/by-name)/dtbo(?:_[ab])?$")
        require(allowed.matches(path)) { "拒绝访问非 DTBO 白名单块设备：$path" }
    }

    companion object {
        fun serializeBackupRecordsJson(records: List<BackupRecord>): String {
            val sb = StringBuilder()
            sb.append("[\n")
            for ((index, r) in records.withIndex()) {
                sb.append("  {\n")
                sb.append("    \"id\": \"${escapeJson(r.id)}\",\n")
                sb.append("    \"fileName\": \"${escapeJson(r.fileName)}\",\n")
                sb.append("    \"filePath\": \"${escapeJson(r.filePath)}\",\n")
                sb.append("    \"backupType\": \"${r.backupType.name}\",\n")
                sb.append("    \"timestamp\": ${r.timestamp},\n")
                sb.append("    \"formattedTime\": \"${escapeJson(r.formattedTime)}\",\n")
                sb.append("    \"androidVersion\": \"${escapeJson(r.androidVersion)}\",\n")
                sb.append("    \"buildDisplay\": \"${escapeJson(r.buildDisplay)}\",\n")
                sb.append("    \"deviceModel\": \"${escapeJson(r.deviceModel)}\",\n")
                sb.append("    \"slot\": \"${escapeJson(r.slot)}\",\n")
                sb.append("    \"blockDevice\": \"${escapeJson(r.blockDevice)}\",\n")
                sb.append("    \"fileSizeBytes\": ${r.fileSizeBytes},\n")
                sb.append("    \"recordedMd5\": \"${escapeJson(r.recordedMd5)}\",\n")
                sb.append("    \"recordedSha256\": \"${escapeJson(r.recordedSha256)}\",\n")
                sb.append("    \"description\": \"${escapeJson(r.description)}\"\n")
                sb.append("  }")
                if (index < records.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]\n")
            return sb.toString()
        }

        fun parseBackupRecordsJson(json: String): List<BackupRecord> {
            val list = mutableListOf<BackupRecord>()
            // Match each JSON object inside array
            val objectRegex = Regex("\\{([^{}]+)\\}")
            val matches = objectRegex.findAll(json)

            for (match in matches) {
                val body = match.groupValues[1]
                val map = mutableMapOf<String, String>()
                // Matches "key": "value" (supporting escapes) or "key": 12345
                val pairRegex = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([\\d-]+))")
                for (pair in pairRegex.findAll(body)) {
                    val key = pair.groupValues[1]
                    val strGroup = pair.groups[2]
                    val numGroup = pair.groups[3]
                    map[key] = when {
                        strGroup != null -> unescapeJson(strGroup.value)
                        numGroup != null -> numGroup.value
                        else -> ""
                    }
                }

                val id = map["id"] ?: continue
                val fileName = map["fileName"] ?: continue
                val filePath = map["filePath"] ?: continue
                val typeStr = map["backupType"] ?: BackupType.AUTO.name
                val backupType = runCatching { BackupType.valueOf(typeStr) }.getOrDefault(BackupType.AUTO)
                val timestamp = map["timestamp"]?.toLongOrNull() ?: 0L
                val formattedTime = map["formattedTime"] ?: ""
                val androidVersion = map["androidVersion"] ?: ""
                val buildDisplay = map["buildDisplay"] ?: ""
                val deviceModel = map["deviceModel"] ?: ""
                val slot = map["slot"] ?: ""
                val blockDevice = map["blockDevice"] ?: ""
                val fileSizeBytes = map["fileSizeBytes"]?.toLongOrNull() ?: 0L
                val recordedMd5 = map["recordedMd5"] ?: ""
                val recordedSha256 = map["recordedSha256"] ?: ""
                val description = map["description"] ?: ""

                list.add(
                    BackupRecord(
                        id = id,
                        fileName = fileName,
                        filePath = filePath,
                        backupType = backupType,
                        timestamp = timestamp,
                        formattedTime = formattedTime,
                        androidVersion = androidVersion,
                        buildDisplay = buildDisplay,
                        deviceModel = deviceModel,
                        slot = slot,
                        blockDevice = blockDevice,
                        fileSizeBytes = fileSizeBytes,
                        recordedMd5 = recordedMd5,
                        recordedSha256 = recordedSha256,
                        description = description
                    )
                )
            }
            return list
        }

        private fun escapeJson(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        private fun unescapeJson(s: String): String {
            return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
    }
}
