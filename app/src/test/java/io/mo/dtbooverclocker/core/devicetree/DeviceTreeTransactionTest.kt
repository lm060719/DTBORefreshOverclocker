package io.mo.dtbooverclocker.core.devicetree

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
}
