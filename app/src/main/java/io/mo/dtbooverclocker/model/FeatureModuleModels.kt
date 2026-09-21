package io.mo.dtbooverclocker.model

import java.util.UUID

enum class FeatureModuleKind(val displayName: String)
{
    RESOLUTION("分辨率"),
    DSC("DSC")
}

enum class ResolutionScope(val displayName: String, val description: String)
{
    MATCHING_GROUP(
        "同步同组档位",
        "修改所选 timing 同一父节点下、且当前分辨率完全一致的全部档位，避免不同刷新率之间出现分辨率不一致。"
    ),
    SELECTED_TIMING(
        "仅当前档位",
        "只修改当前 timing。适合研究和离线验证，不建议作为日常配置。"
    )
}

data class ModuleStagedChange(
    val id: String = UUID.randomUUID().toString(),
    val module: FeatureModuleKind,
    val entryIndex: Int,
    val affectedNodePaths: List<String>,
    val summary: String,
    val changes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val directFlashAllowed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
