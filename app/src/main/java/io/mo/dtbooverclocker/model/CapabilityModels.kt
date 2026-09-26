package io.mo.dtbooverclocker.model

import io.mo.dtbooverclocker.ui.i18n.AppStrings

enum class CapabilityKind(val displayName: String)
{
    REFRESH_RATE("刷新率"),
    CHARGING("Charging");

    fun getDisplayName(strings: AppStrings): String = when (this) {
        REFRESH_RATE -> strings.capabilityRefreshRate
        CHARGING -> strings.capabilityCharging
    }
}

enum class CapabilityStatus(val displayName: String)
{
    AVAILABLE("可用"),
    ANALYSIS_ONLY("可分析"),
    NOT_FOUND("未发现");

    fun getDisplayName(strings: AppStrings): String = when (this) {
        AVAILABLE -> strings.capabilityAvailable
        ANALYSIS_ONLY -> strings.capabilityAnalysisOnly
        NOT_FOUND -> strings.capabilityNotFound
    }
}

data class CapabilityFinding(
    val kind: CapabilityKind,
    val status: CapabilityStatus,
    val matchCount: Int,
    val summary: String,
    val examplePaths: List<String> = emptyList(),
    val sourceHint: String? = null
)

/** 单个 DTB 的扫描结果；增量重扫时未修改的条目直接复用。 */
data class EntryCapabilityScan(
    val entryIndex: Int,
    val nodeCount: Int,
    val propertyCount: Int,
    val chargingNodes: List<ChargingNode>
)

data class CapabilityReport(
    val scannedEntryCount: Int,
    val nodeCount: Int,
    val propertyCount: Int,
    val findings: List<CapabilityFinding>,
    val chargingNodes: List<ChargingNode> = emptyList(),
    val entryScans: List<EntryCapabilityScan> = emptyList()
)
{
    fun finding(kind: CapabilityKind): CapabilityFinding?
    {
        return findings.firstOrNull { it.kind == kind }
    }
}
