package io.mo.dtbooverclocker.model

import java.util.UUID

enum class FeatureModuleKind(val displayName: String)
{
    CHARGING("Charging")
}

data class ModuleStagedChange(
    val id: String = UUID.randomUUID().toString(),
    val module: FeatureModuleKind,
    val entryIndex: Int,
    val affectedNodePaths: List<String>,
    val summary: String,
    val changes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
