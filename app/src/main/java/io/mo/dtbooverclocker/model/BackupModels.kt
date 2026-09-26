package io.mo.dtbooverclocker.model

import io.mo.dtbooverclocker.ui.i18n.AppStrings

enum class BackupType(val displayName: String) {
    AUTO("自动备份"),
    MANUAL("手动备份");

    fun getDisplayName(strings: AppStrings): String = when (this) {
        AUTO -> strings.backupTypeAuto
        MANUAL -> strings.backupTypeManual
    }
}

data class BackupRecord(
    val id: String,
    val fileName: String,
    val filePath: String,
    val backupType: BackupType,
    val timestamp: Long,
    val formattedTime: String,
    val androidVersion: String,
    val buildDisplay: String,
    val deviceModel: String,
    val slot: String,
    val blockDevice: String,
    val fileSizeBytes: Long,
    val recordedMd5: String,
    val recordedSha256: String = "",
    val description: String = ""
)

enum class BackupVerificationStatus(val label: String) {
    UNCHECKED("未校验"),
    VERIFYING("正在校验…"),
    MATCHED("MD5 一致"),
    MISMATCH("MD5 不一致"),
    FILE_MISSING("文件不存在");

    fun getDisplayName(strings: AppStrings): String = when (this) {
        UNCHECKED -> strings.md5Unchecked
        VERIFYING -> strings.md5Verifying
        MATCHED -> strings.md5Matched
        MISMATCH -> strings.md5Mismatch
        FILE_MISSING -> strings.md5FileMissing
    }
}

data class BackupVerificationState(
    val status: BackupVerificationStatus = BackupVerificationStatus.UNCHECKED,
    val computedMd5: String? = null,
    val message: String? = null
)

