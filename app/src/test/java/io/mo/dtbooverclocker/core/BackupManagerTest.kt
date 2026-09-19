package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.BackupRecord
import io.mo.dtbooverclocker.model.BackupType
import io.mo.dtbooverclocker.model.BackupVerificationStatus
import io.mo.dtbooverclocker.util.HashUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupManagerTest {

    @Test
    fun testJsonSerializationAndDeserializationRoundtrip() {
        val originalRecords = listOf(
            BackupRecord(
                id = "id-001",
                fileName = "dtbo_backup_auto_a_20260919_193000.img",
                filePath = "/data/user/0/io.mo.dtbooverclocker/files/backups/dtbo_backup_auto_a_20260919_193000.img",
                backupType = BackupType.AUTO,
                timestamp = 1790000000000L,
                formattedTime = "2026-09-19 19:30:00",
                androidVersion = "Android 14 (API 34)",
                buildDisplay = "UKQ1.230917.001 (1.0.12.0)",
                deviceModel = "Xiaomi 13 Pro",
                slot = "Slot A",
                blockDevice = "/dev/block/by-name/dtbo_a",
                fileSizeBytes = 25165824L,
                recordedMd5 = "5d41402abc4b2a76b9719d911017c592",
                recordedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                description = "刷入 144Hz 前自动备份"
            ),
            BackupRecord(
                id = "id-002",
                fileName = "dtbo_backup_manual_b_20260919_192000.img",
                filePath = "/data/user/0/io.mo.dtbooverclocker/files/backups/dtbo_backup_manual_b_20260919_192000.img",
                backupType = BackupType.MANUAL,
                timestamp = 1789999000000L,
                formattedTime = "2026-09-19 19:20:00",
                androidVersion = "Android 14 (API 34)",
                buildDisplay = "UKQ1.230917.001",
                deviceModel = "Xiaomi 13 Pro",
                slot = "Slot B",
                blockDevice = "/dev/block/by-name/dtbo_b",
                fileSizeBytes = 25165824L,
                recordedMd5 = "e10adc3949ba59abbe56e057f20f883e",
                recordedSha256 = "",
                description = "原厂基准备份"
            )
        )

        val json = BackupManager.serializeBackupRecordsJson(originalRecords)
        assertTrue(json.contains("dtbo_backup_auto_a_20260919_193000.img"))
        assertTrue(json.contains("AUTO"))
        assertTrue(json.contains("MANUAL"))

        val parsed = BackupManager.parseBackupRecordsJson(json)
        assertEquals(2, parsed.size)

        val first = parsed[0]
        assertEquals(originalRecords[0].id, first.id)
        assertEquals(originalRecords[0].fileName, first.fileName)
        assertEquals(originalRecords[0].filePath, first.filePath)
        assertEquals(originalRecords[0].backupType, first.backupType)
        assertEquals(originalRecords[0].timestamp, first.timestamp)
        assertEquals(originalRecords[0].formattedTime, first.formattedTime)
        assertEquals(originalRecords[0].androidVersion, first.androidVersion)
        assertEquals(originalRecords[0].buildDisplay, first.buildDisplay)
        assertEquals(originalRecords[0].deviceModel, first.deviceModel)
        assertEquals(originalRecords[0].slot, first.slot)
        assertEquals(originalRecords[0].blockDevice, first.blockDevice)
        assertEquals(originalRecords[0].fileSizeBytes, first.fileSizeBytes)
        assertEquals(originalRecords[0].recordedMd5, first.recordedMd5)
        assertEquals(originalRecords[0].recordedSha256, first.recordedSha256)
        assertEquals(originalRecords[0].description, first.description)

        val second = parsed[1]
        assertEquals(originalRecords[1].id, second.id)
        assertEquals(originalRecords[1].backupType, second.backupType)
        assertEquals(originalRecords[1].recordedMd5, second.recordedMd5)
    }

    @Test
    fun testEmptyListSerialization() {
        val json = BackupManager.serializeBackupRecordsJson(emptyList())
        val parsed = BackupManager.parseBackupRecordsJson(json)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun testSpecialCharactersEscaping() {
        val records = listOf(
            BackupRecord(
                id = "test-quotes",
                fileName = "test_\"name\".img",
                filePath = "C:\\path\\with\\backslashes\\file.img",
                backupType = BackupType.MANUAL,
                timestamp = 1000L,
                formattedTime = "2026-09-19",
                androidVersion = "Line 1\nLine 2",
                buildDisplay = "Test\tTab",
                deviceModel = "Device \"Pro\"",
                slot = "single",
                blockDevice = "/dev/block/dtbo",
                fileSizeBytes = 1024L,
                recordedMd5 = "abc123",
                recordedSha256 = "",
                description = "Notes with \"quotes\" and \\slash"
            )
        )

        val json = BackupManager.serializeBackupRecordsJson(records)
        val parsed = BackupManager.parseBackupRecordsJson(json)
        assertEquals(1, parsed.size)
        assertEquals(records[0].fileName, parsed[0].fileName)
        assertEquals(records[0].filePath, parsed[0].filePath)
        assertEquals(records[0].androidVersion, parsed[0].androidVersion)
        assertEquals(records[0].description, parsed[0].description)
    }

    @Test
    fun testTimestampSortingOrder() {
        val records = listOf(
            BackupRecord(
                id = "1", fileName = "first.img", filePath = "/tmp/1", backupType = BackupType.AUTO,
                timestamp = 100L, formattedTime = "", androidVersion = "", buildDisplay = "",
                deviceModel = "", slot = "", blockDevice = "", fileSizeBytes = 0L, recordedMd5 = ""
            ),
            BackupRecord(
                id = "3", fileName = "third.img", filePath = "/tmp/3", backupType = BackupType.AUTO,
                timestamp = 300L, formattedTime = "", androidVersion = "", buildDisplay = "",
                deviceModel = "", slot = "", blockDevice = "", fileSizeBytes = 0L, recordedMd5 = ""
            ),
            BackupRecord(
                id = "2", fileName = "second.img", filePath = "/tmp/2", backupType = BackupType.AUTO,
                timestamp = 200L, formattedTime = "", androidVersion = "", buildDisplay = "",
                deviceModel = "", slot = "", blockDevice = "", fileSizeBytes = 0L, recordedMd5 = ""
            )
        )
        val sorted = records.sortedByDescending { it.timestamp }
        assertEquals("3", sorted[0].id)
        assertEquals("2", sorted[1].id)
        assertEquals("1", sorted[2].id)
    }
}
