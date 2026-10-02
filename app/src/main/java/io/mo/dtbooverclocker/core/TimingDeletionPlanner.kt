package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.TimingUtils

/** Validate the whole selection before writing, including the combined effect of mirror deletions. */
internal object TimingDeletionPlanner {
    data class Plan(val targets: List<TimingCandidate>, val skippedEntries: Set<Int>)

    fun panelCandidates(candidates: List<TimingCandidate>, anchor: TimingCandidate): List<TimingCandidate> =
        candidates.filter {
            it.entryIndex == anchor.entryIndex &&
                TimingUtils.parsePanelIdentifier(it.nodePath) == TimingUtils.parsePanelIdentifier(anchor.nodePath)
        }

    fun canDelete(candidates: List<TimingCandidate>, targets: List<TimingCandidate>): Boolean =
        targets.isNotEmpty() && targets.groupBy { it.entryIndex to it.nodePath.substringBeforeLast('/') }
            .all { (parent, deletions) ->
                candidates.count { it.entryIndex == parent.first && it.nodePath.substringBeforeLast('/') == parent.second } > deletions.size
            }

    fun plan(candidates: List<TimingCandidate>, anchor: TimingCandidate, ids: Set<String>, sync: Boolean): Plan {
        val primary = panelCandidates(candidates, anchor).filter { it.id in ids }
        require(primary.isNotEmpty() && primary.size == ids.size) { "待删除档位已失效或不属于当前面板，请重新选择。" }
        require(canDelete(candidates, primary)) { "每个屏幕面板必须保留至少 1 个时序档位，不能删除所选的全部档位。" }
        val mirrors = if (sync) primary.flatMap { TimingUtils.findMirrorCandidates(candidates, it) }
            .distinctBy { it.entryIndex to it.nodePath }.groupBy { it.entryIndex } else emptyMap()
        val skipped = mirrors.filterValues { !canDelete(candidates, it) }.keys
        return Plan(primary + mirrors.filterKeys { it !in skipped }.values.flatten(), skipped)
    }

    fun remainingSelection(candidates: List<TimingCandidate>, anchor: TimingCandidate): String? {
        val panel = panelCandidates(candidates, anchor)
        return (panel.firstOrNull { it.nodePath == anchor.nodePath }
            ?: panel.firstOrNull { it.nodePath.substringBeforeLast('/') == anchor.nodePath.substringBeforeLast('/') }
            ?: panel.firstOrNull())?.id
    }
}
