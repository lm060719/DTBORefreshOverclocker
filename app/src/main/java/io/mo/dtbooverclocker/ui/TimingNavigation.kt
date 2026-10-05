package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.TimingUtils

/** Resolve an exact panel, preserving an explicit DTB or preferring the device's active DTB. */
internal fun MainUiState.overviewTimingCandidate(
    panelIdentifier: String,
    entryIndex: Int? = null,
    nodePath: String? = null
): TimingCandidate? {
    val panelCandidates = workspace?.candidates.orEmpty().filter {
        TimingUtils.parsePanelIdentifier(it.nodePath) == panelIdentifier
    }
    val candidates = if (entryIndex != null) {
        panelCandidates.filter { it.entryIndex == entryIndex }
    } else {
        panelCandidates.filter { it.entryIndex in activeDtboEntries }.ifEmpty { panelCandidates }
    }
    return candidates.firstOrNull { it.nodePath == nodePath }
        ?: candidates.firstOrNull { it.id == selectedCandidateId }
        ?: candidates.firstOrNull { !it.hasVendorDynamicMode }
        ?: candidates.firstOrNull()
}

/** Navigation edits the selection and its draft target, never the staged transactions or output. */
internal fun MainUiState.selectTimingCandidate(id: String): MainUiState {
    if (busy || selectedCandidateId == id) return this
    val candidate = workspace?.candidates?.firstOrNull { it.id == id } ?: return this
    return copy(selectedCandidateId = id, targetHz = suggestedTimingTarget(candidate.currentHz), templateNodeName = null)
}

/** 当前生效的命令模板：仅编辑档位模式下、且确实是 [candidate] 的同级档位时才生效。 */
internal fun MainUiState.effectiveTemplate(candidate: TimingCandidate): TimingCandidate? {
    val name = templateNodeName?.takeIf { patchMode == PatchMode.OVERWRITE_EXISTING } ?: return null
    return TimingUtils.findTemplateSiblings(workspace?.candidates.orEmpty(), candidate)
        .singleOrNull { TimingUtils.parseTimingNodeName(it.nodePath) == name }
}

internal fun suggestedTimingTarget(currentHz: Int): Int = when {
    currentHz < 60 -> 60
    currentHz < 90 -> 90
    currentHz < 120 -> 120
    currentHz < 144 -> 144
    currentHz < 165 -> 165
    else -> (currentHz + 15).coerceAtMost(240)
}
