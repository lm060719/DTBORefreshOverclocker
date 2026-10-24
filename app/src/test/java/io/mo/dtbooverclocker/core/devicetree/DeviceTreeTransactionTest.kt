package io.mo.dtbooverclocker.core.devicetree

import io.mo.dtbooverclocker.model.FeatureModuleKind
import io.mo.dtbooverclocker.model.ModuleStagedChange
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.StagedChange
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTreeTransactionTest
{
    @Test
    fun groupsRefreshOperationsAsOneLogicalTransaction()
    {
        val operationA = SetPropertyChange(
            entryIndex = 0,
            nodePath = "/panel/timing@120",
            propertyName = "qcom,mdss-dsi-panel-framerate",
            oldRawValue = "<120>",
            newRawValue = "<144>"
        )
        val operationB = SetPropertyChange(
            entryIndex = 0,
            nodePath = "/panel/timing@120",
            propertyName = "qcom,mdss-dsi-panel-clockrate",
            oldRawValue = "<1360000000>",
            newRawValue = "<1632000000>"
        )
        val staged = StagedChange(
            mode = PatchMode.OVERWRITE_EXISTING,
            entryIndex = 0,
            nodePath = "/panel/timing@120",
            nodeName = "timing@120",
            originalHz = 120,
            targetHz = 144,
            strategy = PatchStrategy.PIXEL_CLOCK_ONLY,
            summary = "120 Hz → 144 Hz"
        )

        val transaction = DeviceTreeTransaction.refreshRate(
            stagedChange = staged,
            operations = listOf(operationA, operationB),
            warnings = emptyList()
        )

        assertEquals(DeviceTreeTransactionKind.REFRESH_RATE, transaction.kind)
        assertEquals(2, transaction.operationCount)
        assertEquals(setOf(0), transaction.entryIndices)
        assertEquals(DeviceTreeTransactionRisk.TRUSTED, transaction.risk)
        assertEquals(staged, transaction.timingChange)
    }

    @Test
    fun genericEditIsMarkedCaution()
    {
        val change = SetPropertyChange(
            entryIndex = 1,
            nodePath = "/soc/node@0",
            propertyName = "status",
            oldRawValue = "\"disabled\"",
            newRawValue = "\"okay\""
        )

        val transaction = DeviceTreeTransaction.generic(change)

        assertEquals(DeviceTreeTransactionRisk.CAUTION, transaction.risk)
        assertEquals(listOf(change), transaction.operations)
        assertEquals(setOf(1), listOf(transaction).modifiedEntryIndices())
    }

    @Test
    fun compactSummaryMergesSameKindWithoutNodeNames()
    {
        val transactions = listOf(
            timing(PatchMode.DELETE_EXISTING, 60, 60),
            timing(PatchMode.DELETE_EXISTING, 120, 120)
        )

        assertEquals("删除 60 / 120 Hz", transactions.compactSummary())
    }

    @Test
    fun compactSummaryKeepsFirstAppearanceOrderAcrossKinds()
    {
        val transactions = listOf(
            timing(PatchMode.OVERWRITE_EXISTING, 120, 144),
            timing(PatchMode.DELETE_EXISTING, 60, 60),
            charging(3),
            timing(PatchMode.APPEND_NEW, 120, 165, dfps = true),
            timing(PatchMode.APPEND_NEW, 120, 144),
            DeviceTreeTransaction.generic(property("/soc/node@0")),
            timing(PatchMode.OVERWRITE_EXISTING, 90, 100),
            charging(2)
        )

        assertEquals(
            "120 → 144 / 90 → 100 Hz，删除 60 Hz，充电参数 5 项，DFPS 新增 165 Hz，新增 144 Hz，设备树编辑 1 处",
            transactions.compactSummary()
        )
    }

    @Test
    fun compactSummaryTruncatesWholeKindsWithTotalCount()
    {
        val transactions = listOf(
            timing(PatchMode.DELETE_EXISTING, 60, 60),
            timing(PatchMode.APPEND_NEW, 120, 144),
            timing(PatchMode.OVERWRITE_EXISTING, 90, 100)
        )

        assertEquals("删除 60 Hz，新增 144 Hz 等 3 项", transactions.compactSummary(maxLength = 25))
        assertEquals("", emptyList<DeviceTreeTransaction>().compactSummary())
    }

    private fun property(nodePath: String) = SetPropertyChange(
        entryIndex = 0,
        nodePath = nodePath,
        propertyName = "status",
        oldRawValue = "\"disabled\"",
        newRawValue = "\"okay\""
    )

    private fun timing(mode: PatchMode, originalHz: Int, targetHz: Int, dfps: Boolean = false) =
        DeviceTreeTransaction.refreshRate(
            stagedChange = StagedChange(
                mode = mode,
                entryIndex = 0,
                nodePath = "/panel/timing@$originalHz",
                nodeName = "timing@$originalHz",
                originalHz = originalHz,
                targetHz = targetHz,
                strategy = PatchStrategy.PIXEL_CLOCK_ONLY,
                dfps = dfps,
                summary = "timing@$originalHz"
            ),
            operations = listOf(property("/panel/timing@$originalHz")),
            warnings = emptyList()
        )

    private fun charging(operationCount: Int) = DeviceTreeTransaction.charging(
        ModuleStagedChange(
            module = FeatureModuleKind.CHARGING,
            entryIndex = 0,
            affectedNodePaths = listOf("/soc/charger"),
            summary = "Charging"
        ),
        List(operationCount) { property("/soc/charger") }
    )
}
