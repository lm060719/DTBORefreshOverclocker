package io.mo.dtbooverclocker.model

import java.io.File

enum class SourceMode {
    ROOT_PARTITION,
    LOCAL_IMAGE
}

enum class PatchStrategy(val displayName: String, val description: String) {
    BALANCED_BLANKING_TIME(
        "平衡时序",
        "同步调整 Pixel Clock 与垂直前/后肩，使目标刷新率满足时序公式。"
    ),
    PIXEL_CLOCK_ONLY(
        "仅 Pixel Clock",
        "保持 porch 不变，仅按刷新率比例调整 Pixel Clock。"
    ),
    FRAMERATE_ONLY(
        "仅 Framerate",
        "只修改刷新率属性。兼容性最高但风险也最高，不建议用于直接刷写。"
    )
}

enum class PatchMode(val displayName: String, val description: String) {
    OVERWRITE_EXISTING(
        "覆盖修改档位",
        "将选中的原始时序档位直接超频为目标刷新率（替换原档位）。"
    ),
    APPEND_NEW(
        "新增独立档位",
        "完整保留原有时序档位，以此档位为蓝本克隆并追加全新的刷新率节点。"
    )
}

data class RootState(
    val suPresent: Boolean = false,
    val granted: Boolean = false,
    val detail: String = "未检测"
)

data class SlotInfo(
    val suffix: String,
    val label: String,
    val blockDevice: String,
    val oppositeSuffix: String?
) {
    val fastbootPartition: String
        get() = if (suffix.isBlank()) "dtbo" else "dtbo$suffix"

    val oppositeFastbootSlot: String?
        get() = oppositeSuffix?.removePrefix("_")
}

data class CommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0
}

data class DtboEntryMetadata(
    val index: Int,
    val storedSize: Int,
    val storedOffset: Int,
    val idHex: String,
    val revHex: String,
    val flagsHex: String?,
    val customHex: List<String>,
    val compressionFormat: Int
)

data class DtboMetadata(
    val magicHex: String,
    val totalSize: Int,
    val headerSize: Int,
    val entrySize: Int,
    val entriesOffset: Int,
    val pageSize: Int,
    val version: Int,
    val entries: List<DtboEntryMetadata>
)

data class DtboBinaryEntry(
    val metadata: DtboEntryMetadata,
    val storedBytes: ByteArray,
    val decodedBytes: ByteArray
)

data class DtboBinaryImage(
    val metadata: DtboMetadata,
    val prefixTemplate: ByteArray,
    val entries: List<DtboBinaryEntry>
)

data class TimingCandidate(
    val id: String,
    val entryIndex: Int,
    val dtsFile: File,
    val nodePath: String,
    val nodeStart: Int,
    val nodeEndExclusive: Int,
    val currentHz: Int,
    val pixelClockHz: Long? = null,
    val hActive: Int? = null,
    val vActive: Int? = null,
    val hFrontPorch: Int? = null,
    val hBackPorch: Int? = null,
    val hSync: Int? = null,
    val vFrontPorch: Int? = null,
    val vBackPorch: Int? = null,
    val vSync: Int? = null,
    val hasOpaquePanelTimings: Boolean = false
) {
    val hasFullGeometry: Boolean
        get() = listOf(
            hActive,
            vActive,
            hFrontPorch,
            hBackPorch,
            hSync,
            vFrontPorch,
            vBackPorch,
            vSync
        ).all { it != null }
}

data class DtboWorkspace(
    val rootDir: File,
    val inputImage: File,
    val metadataFile: File,
    val metadata: DtboMetadata,
    val binaryImage: DtboBinaryImage,
    val extractedEntries: List<File>,
    val dtsFiles: List<File>,
    val candidates: List<TimingCandidate>
)

data class PatchReport(
    val outputImage: File,
    val targetHz: Int,
    val originalHz: Int,
    val strategy: PatchStrategy,
    val mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
    val changes: List<String>,
    val warnings: List<String>
)

data class FlashResult(
    val backupFile: File,
    val backupSha256: String,
    val backupExternalUri: String?,
    val rescueZip: File,
    val rescueExternalUri: String?,
    val flashedPartition: String,
    val patchedSha256: String,
    val readBackVerified: Boolean,
    val rollbackCommands: List<String>
)
