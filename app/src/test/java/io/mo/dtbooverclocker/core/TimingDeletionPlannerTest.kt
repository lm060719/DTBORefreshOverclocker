package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.TimingCandidate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TimingDeletionPlannerTest {
    private fun candidate(entry: Int, index: Int, hz: Int, panel: String = "test_panel", group: String = "display-timings") =
        TimingCandidate("$entry:$panel:$group:$index", entry, File("$entry.dts"), "/$panel/$group/timing@$index", 0, 1, hz)
    private val first = candidate(1, 0, 60)
    private val second = candidate(1, 1, 90)
    private val remaining = candidate(1, 2, 120)
    private val original = listOf(first, second, remaining)

    @Test fun multipleSelectionsStayWithinCurrentPanelAndDtb() {
        val other = candidate(1, 0, 60, "test_panel_dvt02")
        val plan = TimingDeletionPlanner.plan(original + other, first, setOf(first.id, second.id), false)
        assertEquals(listOf(first, second), plan.targets)
        assertTrue(plan.skippedEntries.isEmpty())
    }

    @Test fun refusesEmptyStaleOtherPanelAndOtherDtbSelections() {
        val otherPanel = candidate(1, 0, 60, "other_panel")
        val otherDtb = candidate(0, 0, 60)
        for (ids in listOf(emptySet(), setOf("stale"), setOf(first.id, "stale"), setOf(otherPanel.id), setOf(otherDtb.id))) {
            assertThrows(IllegalArgumentException::class.java) {
                TimingDeletionPlanner.plan(original + otherPanel + otherDtb, first, ids, false)
            }
        }
    }

    @Test fun refusesDeletingAllTimingsEvenWhenOtherPanelsExist() {
        val other = candidate(1, 0, 60, "other_panel")
        assertThrows(IllegalArgumentException::class.java) {
            TimingDeletionPlanner.plan(original + other, first, original.map { it.id }.toSet(), false)
        }
    }

    @Test fun preservesOneModeInEachTimingGroup() {
        val onlyInGroup = candidate(1, 0, 60, group = "alternate-display-timings")
        assertFalse(TimingDeletionPlanner.canDelete(original + onlyInGroup, listOf(onlyInGroup)))
        assertThrows(IllegalArgumentException::class.java) {
            TimingDeletionPlanner.plan(original + onlyInGroup, first, setOf(first.id, onlyInGroup.id), false)
        }
    }

    @Test fun syncSkipsWholeEntryWhenCombinedDeletionsWouldEmptyItsPanel() {
        val mirrors = listOf(candidate(0, 0, 60), candidate(0, 1, 90))
        val other = candidate(0, 0, 120, "other_panel")
        val safeMirrors = listOf(candidate(2, 0, 60), candidate(2, 1, 90), candidate(2, 2, 120))
        val plan = TimingDeletionPlanner.plan(original + mirrors + other + safeMirrors,
            first, setOf(first.id, second.id), true)
        assertEquals(setOf(0), plan.skippedEntries)
        assertEquals(listOf(first, second, safeMirrors[0], safeMirrors[1]), plan.targets)
    }

    @Test fun syncLeavesUnmatchedTimingsUntouched() {
        val unmatched = candidate(0, 0, 75)
        val match = candidate(0, 1, 90)
        val plan = TimingDeletionPlanner.plan(original + listOf(unmatched, match), first,
            setOf(first.id, second.id), true)
        assertEquals(listOf(first, second, match), plan.targets)
    }

    @Test fun remainingSelectionRetainsOriginalNodeOrFallsBackWithinOriginalDtbAndPanel() {
        val elsewhere = candidate(0, 2, 120)
        val other = candidate(1, 2, 120, "test_panel_dvt02")
        val refreshed = first.copy(id = "new-offset")
        assertEquals(refreshed.id, TimingDeletionPlanner.remainingSelection(listOf(other, elsewhere, refreshed, remaining), first))
        assertEquals(remaining.id, TimingDeletionPlanner.remainingSelection(listOf(other, elsewhere, remaining), first))
    }
}
