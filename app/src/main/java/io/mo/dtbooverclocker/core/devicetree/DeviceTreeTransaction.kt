package io.mo.dtbooverclocker.core.devicetree

import io.mo.dtbooverclocker.model.ModuleStagedChange
import io.mo.dtbooverclocker.model.StagedChange
import java.util.UUID

enum class DeviceTreeTransactionKind(val displayName: String)
{
    REFRESH_RATE("刷新率"),
    RESOLUTION("分辨率"),
    DSC("DSC"),
    GENERIC_EDIT("设备树编辑")
}

enum class DeviceTreeTransactionRisk(val displayName: String)
{
    TRUSTED("受信任"),
    CAUTION("谨慎"),
    EXPORT_ONLY("仅导出验证")
}

/**
 * 一次用户可感知的设备树逻辑修改事务。
 *
 * 一个事务可以包含多个底层 DeviceTreeChange。MainUiState 以 transactions 作为唯一暂存源，
 * 刷新率、分辨率和通用编辑的旧列表都由事务派生，避免多套状态彼此漂移。
 *
 * directFlashAllowed 是部署安全边界的一部分：只要队列中存在 export-only 事务，
 * 应用就必须禁止 Root 直接刷写。
 */
data class DeviceTreeTransaction(
    val id: String = UUID.randomUUID().toString(),
    val kind: DeviceTreeTransactionKind,
    val summary: String,
    val operations: List<DeviceTreeChange>,
    val risk: DeviceTreeTransactionRisk,
    val directFlashAllowed: Boolean,
    val warnings: List<String> = emptyList(),
    val timingChange: StagedChange? = null,
    val moduleChange: ModuleStagedChange? = null,
    val timestamp: Long = System.currentTimeMillis()
)
{
    init
    {
        require(operations.isNotEmpty()) {
            "DeviceTreeTransaction 至少需要一个底层 DeviceTreeChange"
        }
        require(operations.map { it.entryIndex }.distinct().isNotEmpty()) {
            "DeviceTreeTransaction 必须关联有效 DTB Entry"
        }
    }

    val entryIndices: Set<Int>
        get() = operations.map { it.entryIndex }.toSet()

    val operationCount: Int
        get() = operations.size

    companion object
    {
        fun refreshRate(
            stagedChange: StagedChange,
            operations: List<DeviceTreeChange>,
            warnings: List<String>,
            directFlashAllowed: Boolean
        ): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                kind = DeviceTreeTransactionKind.REFRESH_RATE,
                summary = stagedChange.summary,
                operations = operations,
                risk = if (directFlashAllowed) DeviceTreeTransactionRisk.TRUSTED else DeviceTreeTransactionRisk.CAUTION,
                directFlashAllowed = directFlashAllowed,
                warnings = warnings,
                timingChange = stagedChange
            )
        }

        fun resolution(
            moduleChange: ModuleStagedChange,
            operations: List<DeviceTreeChange>
        ): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                kind = DeviceTreeTransactionKind.RESOLUTION,
                summary = moduleChange.summary,
                operations = operations,
                risk = DeviceTreeTransactionRisk.EXPORT_ONLY,
                directFlashAllowed = moduleChange.directFlashAllowed,
                warnings = moduleChange.warnings,
                moduleChange = moduleChange
            )
        }

        fun dsc(moduleChange: ModuleStagedChange, operations: List<DeviceTreeChange>): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                kind = DeviceTreeTransactionKind.DSC,
                summary = moduleChange.summary,
                operations = operations,
                risk = DeviceTreeTransactionRisk.EXPORT_ONLY,
                directFlashAllowed = false,
                warnings = moduleChange.warnings,
                moduleChange = moduleChange
            )
        }

        fun generic(change: DeviceTreeChange): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                kind = DeviceTreeTransactionKind.GENERIC_EDIT,
                summary = change.summary,
                operations = listOf(change),
                risk = DeviceTreeTransactionRisk.EXPORT_ONLY,
                directFlashAllowed = false,
                warnings = listOf("通用设备树自由编辑当前阶段仅允许导出验证，禁止 Root 直刷。")
            )
        }
    }
}

fun List<DeviceTreeTransaction>.allOperations(): List<DeviceTreeChange>
{
    return flatMap { it.operations }
}

fun List<DeviceTreeTransaction>.modifiedEntryIndices(): Set<Int>
{
    return flatMap { it.entryIndices }.toSet()
}
