package io.mo.dtbooverclocker.model

enum class CapabilityKind(val displayName: String)
{
    REFRESH_RATE("刷新率"),
    RESOLUTION("分辨率"),
    DSC("DSC"),
    BRIGHTNESS_HBM("亮度 / HBM"),
    THERMAL("Thermal"),
    CHARGING("Charging"),
    TOUCH("Touch")
}

enum class CapabilityStatus(val displayName: String)
{
    AVAILABLE("可用"),
    ANALYSIS_ONLY("可分析"),
    DETECTED("已发现"),
    NOT_FOUND("未发现")
}

data class CapabilityFinding(
    val kind: CapabilityKind,
    val status: CapabilityStatus,
    val matchCount: Int,
    val summary: String,
    val examplePaths: List<String> = emptyList(),
    val sourceHint: String? = null
)

enum class DscIssueSeverity
{
    INFO,
    WARNING,
    ERROR
}

data class DscIssue(
    val severity: DscIssueSeverity,
    val message: String
)

data class DscTopology(
    val entryIndex: Int,
    val nodePath: String,
    val refreshHz: Int?,
    val panelWidth: Int?,
    val panelHeight: Int?,
    val compressionMode: String?,
    val version: Int?,
    val scrVersionRaw: String?,
    val bitsPerComponent: Int?,
    val bitsPerPixel: Int?,
    val blockPredictionEnabled: Boolean,
    val sliceWidth: Int?,
    val sliceHeight: Int?,
    val slicePerPacket: Int?,
    val horizontalSliceCount: Int?,
    val verticalSliceCount: Int?,
    val slicesPerFrame: Int?,
    val roiAlignment: List<Long>?,
    val issues: List<DscIssue>
)
{
    val hasErrors: Boolean
        get() = issues.any { it.severity == DscIssueSeverity.ERROR }

    val versionDisplay: String?
        get() = version?.let { raw ->
            val major = (raw ushr 4) and 0x0f
            val minor = raw and 0x0f
            "$major.$minor"
        }
}

data class CapabilityReport(
    val scannedEntryCount: Int,
    val nodeCount: Int,
    val propertyCount: Int,
    val findings: List<CapabilityFinding>,
    val dscTopologies: List<DscTopology>,
    val chargingNodes: List<ChargingNode> = emptyList()
)
{
    fun finding(kind: CapabilityKind): CapabilityFinding?
    {
        return findings.firstOrNull { it.kind == kind }
    }
}
