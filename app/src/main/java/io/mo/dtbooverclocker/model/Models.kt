package io.mo.dtbooverclocker.model

import io.mo.dtbooverclocker.ui.i18n.AppStrings
import java.io.File

enum class SourceMode {
    ROOT_PARTITION,
    LOCAL_IMAGE
}

enum class AvbProtectionState {
    NONE,
    UNSIGNED,
    SIGNED
}

enum class PatchStrategy(val displayName: String, val description: String) {
    BALANCED_BLANKING_TIME(
        "平衡时序",
        "普通时序调整时钟与垂直前后肩；含 MDP 传输预算的命令模式保留前后肩，同步调整时钟与传输时间。"
    ),
    PIXEL_CLOCK_ONLY(
        "仅 Pixel Clock",
        "保持 porch 不变，按刷新率比例调整时钟，并同步缩放已有的 MDP 传输时间。"
    ),
    FRAMERATE_ONLY(
        "仅 Framerate",
        "只修改刷新率属性。兼容性最高但风险也最高，不建议用于直接刷写。"
    ),
    CUSTOM(
        "自定义参数",
        "手动指定 Pixel Clock、垂直前肩 (VFP)、垂直后肩 (VBP) 及水平消隐等时序参数。"
    );

    fun getDisplayName(strings: AppStrings): String = when (this) {
        BALANCED_BLANKING_TIME -> strings.strategyBalancedName
        PIXEL_CLOCK_ONLY -> strings.strategyPixelClockName
        FRAMERATE_ONLY -> strings.strategyFramerateName
        CUSTOM -> strings.strategyCustomName
    }

    fun getDescription(strings: AppStrings): String = when (this) {
        BALANCED_BLANKING_TIME -> strings.strategyBalancedDesc
        PIXEL_CLOCK_ONLY -> strings.strategyPixelClockDesc
        FRAMERATE_ONLY -> strings.strategyFramerateDesc
        CUSTOM -> strings.strategyCustomDesc
    }
}

enum class PatchMode(val displayName: String, val description: String) {
    OVERWRITE_EXISTING(
        "编辑修改档位",
        "将选中的原始时序档位直接超频为目标刷新率（替换原档位）。"
    ),
    APPEND_NEW(
        "新增独立档位",
        "完整保留原有时序档位，以此档位为蓝本克隆并追加全新的刷新率节点。"
    ),
    DELETE_EXISTING(
        "删除指定档位",
        "从设备树中彻底移除所选的时序档位节点（需保留至少一个档位以供显示驱动初始化）。"
    );

    fun getDisplayName(strings: AppStrings): String = when (this) {
        OVERWRITE_EXISTING -> strings.patchModeOverwriteName
        APPEND_NEW -> strings.patchModeAppendName
        DELETE_EXISTING -> strings.patchModeDeleteName
    }

    fun getDescription(strings: AppStrings): String = when (this) {
        OVERWRITE_EXISTING -> strings.patchModeOverwriteDesc
        APPEND_NEW -> strings.patchModeAppendDesc
        DELETE_EXISTING -> strings.patchModeDeleteDesc
    }
}

data class CustomTimingParams(
    val pixelClockHz: Long? = null,
    val vFrontPorch: Int? = null,
    val vBackPorch: Int? = null,
    val hFrontPorch: Int? = null,
    val hBackPorch: Int? = null
)

data class StagedChange(
    val id: String = java.util.UUID.randomUUID().toString(),
    val mode: PatchMode,
    val entryIndex: Int,
    val nodePath: String,
    val nodeName: String,
    val originalHz: Int,
    val targetHz: Int,
    val strategy: PatchStrategy,
    val customParams: CustomTimingParams? = null,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

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
    val entries: List<DtboBinaryEntry>,
    val originalBytes: ByteArray? = null
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
    val hasOpaquePanelTimings: Boolean = false,
    val mdpTransferTimeUs: Long? = null,
    val hasVendorDynamicMode: Boolean = false
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

/** Describes the original container; a Root cache file is not a persistent partition backup. */
data class DtboSourceImage(
    val sourceMode: SourceMode,
    val sourcePath: String,
    val sha256: String,
    val containerSize: Int,
    val dtboTotalSize: Int,
    val logicalImageSize: Int?,
    val footerOffset: Int?,
    val avbProtectionState: AvbProtectionState = AvbProtectionState.NONE,
    val avbAlgorithm: String? = null
)

data class DtboWorkspace(
    val rootDir: File,
    val inputImage: File,
    val metadataFile: File,
    val metadata: DtboMetadata,
    val binaryImage: DtboBinaryImage,
    val extractedEntries: List<File>,
    val dtsFiles: List<File>,
    val candidates: List<TimingCandidate>,
    val sourceImage: DtboSourceImage? = null
)

data class PatchReport(
    val outputImage: File,
    val targetHz: Int,
    val originalHz: Int,
    val strategy: PatchStrategy,
    val mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
    val customParams: CustomTimingParams? = null,
    val stagedChanges: List<StagedChange> = emptyList(),
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
