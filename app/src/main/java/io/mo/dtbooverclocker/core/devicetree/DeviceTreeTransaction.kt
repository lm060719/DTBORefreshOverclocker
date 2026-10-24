package io.mo.dtbooverclocker.core.devicetree

import io.mo.dtbooverclocker.model.ModuleStagedChange
import io.mo.dtbooverclocker.model.PatchMode
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

/**
 * 模块 description 等单行场景用的精简摘要：不带节点名，同类修改合并，
 * 例如 "删除 60 / 120 Hz，新增 144 Hz"。超过 [maxLength] 时舍弃靠后的分类，以 "等 N 项" 结尾。
 */
fun List<DeviceTreeTransaction>.compactSummary(maxLength: Int = 100): String
{
    // groupBy 按各分类首次出现的顺序输出
    val parts = groupBy { it.compactCategory() }.map { (category, transactions) ->
        when (category)
        {
            CompactCategory.CHARGING -> "充电参数 ${transactions.sumOf { it.operationCount }} 项"
            CompactCategory.GENERIC -> "设备树编辑 ${transactions.sumOf { it.operationCount }} 处"
            else ->
            {
                val rates = transactions.mapNotNull { it.timingChange?.compactRate() }.distinct()
                "${category.label} ${rates.joinToString(" / ")} Hz".trim()
            }
        }
    }
    val full = parts.joinToString("，")
    if (full.length <= maxLength) return full

    val suffix = " 等 $size 项"
    val kept = mutableListOf(parts.first())
    for (part in parts.drop(1))
    {
        if ((kept + part).joinToString("，").length + suffix.length > maxLength) break
        kept += part
    }
    return kept.joinToString("，") + suffix
}

private enum class CompactCategory(val label: String)
{
    DELETE("删除"),
    APPEND("新增"),
    EDIT(""),
    DFPS_APPEND("DFPS 新增"),
    CHARGING("充电参数"),
    GENERIC("设备树编辑")
}

private fun DeviceTreeTransaction.compactCategory(): CompactCategory
{
    val timing = timingChange
        ?: return if (kind == DeviceTreeTransactionKind.CHARGING) CompactCategory.CHARGING else CompactCategory.GENERIC
    return when (timing.mode)
    {
        PatchMode.DELETE_EXISTING -> CompactCategory.DELETE
        PatchMode.APPEND_NEW -> if (timing.dfps) CompactCategory.DFPS_APPEND else CompactCategory.APPEND
        PatchMode.OVERWRITE_EXISTING -> CompactCategory.EDIT
    }
}

private fun StagedChange.compactRate(): String = when (mode)
{
    PatchMode.DELETE_EXISTING -> "$originalHz"
    PatchMode.APPEND_NEW -> "$targetHz"
    PatchMode.OVERWRITE_EXISTING -> "$originalHz → $targetHz"
}
