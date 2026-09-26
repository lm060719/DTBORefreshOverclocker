package io.mo.dtbooverclocker.core.devicetree

import io.mo.dtbooverclocker.model.ModuleStagedChange
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.StagedChange
import java.util.UUID

import io.mo.dtbooverclocker.ui.i18n.AppStrings

enum class DeviceTreeTransactionKind(val displayName: String)
{
    REFRESH_RATE("刷新率"),
    CHARGING("Charging"),
    GENERIC_EDIT("设备树编辑");

    fun getDisplayName(strings: AppStrings): String = when (this) {
        REFRESH_RATE -> strings.capabilityRefreshRate
        CHARGING -> strings.capabilityCharging
        GENERIC_EDIT -> strings.deviceTreeTitle
    }
}

enum class DeviceTreeTransactionRisk(val displayName: String)
{
    TRUSTED("受信任"),
    CAUTION("谨慎")
}

/**
 * 一次用户可感知的设备树逻辑修改事务。
 *
 * 一个事务可以包含多个底层 DeviceTreeChange。MainUiState 以 transactions 作为唯一暂存源，
 * 刷新率、功能模块和通用编辑的旧列表都由事务派生，避免多套状态彼此漂移。
 */
data class DeviceTreeTransaction(
    val id: String = UUID.randomUUID().toString(),
    val kind: DeviceTreeTransactionKind,
    val summary: String,
    val operations: List<DeviceTreeChange>,
    val risk: DeviceTreeTransactionRisk,
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
            id: String = UUID.randomUUID().toString()
        ): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                id = id,
                kind = DeviceTreeTransactionKind.REFRESH_RATE,
                summary = stagedChange.summary,
                operations = operations,
                risk = if (stagedChange.strategy == PatchStrategy.FRAMERATE_ONLY) {
                    DeviceTreeTransactionRisk.CAUTION
                } else {
                    DeviceTreeTransactionRisk.TRUSTED
                },
                warnings = warnings,
                timingChange = stagedChange
            )
        }

        fun charging(moduleChange: ModuleStagedChange, operations: List<DeviceTreeChange>): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                kind = DeviceTreeTransactionKind.CHARGING,
                summary = moduleChange.summary,
                operations = operations,
                risk = DeviceTreeTransactionRisk.CAUTION,
                warnings = moduleChange.warnings,
                moduleChange = moduleChange
            )
        }

        fun generic(
            change: DeviceTreeChange,
            id: String = UUID.randomUUID().toString()
        ): DeviceTreeTransaction
        {
            return DeviceTreeTransaction(
                id = id,
                kind = DeviceTreeTransactionKind.GENERIC_EDIT,
                summary = change.summary,
                operations = listOf(change),
                risk = DeviceTreeTransactionRisk.CAUTION,
                warnings = listOf("通用设备树自由编辑未经语义校验，刷写前请确认修改内容。")
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
