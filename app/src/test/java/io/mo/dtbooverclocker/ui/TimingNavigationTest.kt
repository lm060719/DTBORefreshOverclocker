package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TimingNavigationTest {
    private val panel = "qcom,mdss_dsi_o1_38_cmd"
    private fun candidate(id: String, entry: Int, identifier: String = panel) =
        TimingCandidate(id, entry, File("$entry.dts"), "/$identifier/display-timings/timing@$id", 0, 1, 60)

    private fun state(vararg candidates: TimingCandidate): MainUiState {
        val metadata = DtboMetadata("0xd7b7ab1e", 0, 32, 32, 32, 4096, 0, emptyList())
        val workspace = DtboWorkspace(File("test"), File("dtbo.img"), File("metadata"), metadata,
            DtboBinaryImage(metadata, byteArrayOf(), emptyList()), emptyList(), emptyList(), candidates.toList())
        return MainUiState(workspace = workspace)
    }

    @Test fun previewResolvesExactDtbAndNodeInsteadOfCurrentSelection() {
        val first = candidate("first", 0)
        val wanted = candidate("wanted", 1)
        val current = state(first, wanted).copy(selectedCandidateId = first.id, activeDtboEntries = setOf(0))
        assertEquals(wanted, current.overviewTimingCandidate(panel, 1, wanted.nodePath))
    }

    @Test fun activePanelPrefersBootDtbAndRetainsSelectionWithinThatDtb() {
        val otherEntry = candidate("other", 0)
        val first = candidate("first", 1)
        val selected = candidate("selected", 1)
        val current = state(otherEntry, first, selected).copy(activeDtboEntries = setOf(1))
        assertEquals(first, current.copy(selectedCandidateId = otherEntry.id).overviewTimingCandidate(panel))
        assertEquals(selected, current.copy(selectedCandidateId = selected.id).overviewTimingCandidate(panel))
    }

    @Test fun deletedNodeFallsBackWithinSamePanelAndDtbWithoutMatchingSiblingPanels() {
        val remaining = candidate("remaining", 1)
        val sibling = candidate("sibling", 1, "${panel}_dvt02")
        val elsewhere = candidate("elsewhere", 0)
        val current = state(sibling, elsewhere, remaining)
        assertEquals(remaining, current.overviewTimingCandidate(panel, 1, "/$panel/display-timings/timing@deleted"))
        assertNull(current.overviewTimingCandidate(panel, 2))
        assertNull(current.overviewTimingCandidate("missing"))
    }

    @Test fun selectingCandidatePreservesTransactionsOutputAndRescueInformation() {
        val first = candidate("first", 0)
        val wanted = candidate("wanted", 1)
        val transaction = DeviceTreeTransaction.generic(SetPropertyChange(0, "/", "test", "<1>", "<2>"))
        val report = PatchReport(File("patched.img"), 75, 60, PatchStrategy.PIXEL_CLOCK_ONLY,
            changes = listOf("test"), warnings = emptyList())
        val flash = FlashResult(File("backup.img"), "backup", null, File("rescue.zip"), null,
            "dtbo_a", "patched", true, listOf("fastboot flash dtbo_a backup.img"))
        val current = state(first, wanted).copy(selectedCandidateId = first.id, targetHz = 153,
            transactions = listOf(transaction), patchReport = report, lastFlash = flash, customVfpText = "12")
        assertEquals(current.copy(selectedCandidateId = wanted.id, targetHz = 90), current.selectTimingCandidate(wanted.id))
        assertSame(current, current.selectTimingCandidate(first.id))
        assertSame(current, current.selectTimingCandidate("missing"))
        val busy = current.copy(busy = true)
        assertSame(busy, busy.selectTimingCandidate(wanted.id))
    }
}
