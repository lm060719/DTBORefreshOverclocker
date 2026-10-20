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
    // DFPS 面板的实际上限是列表最高值，建议目标从它往上算。
    val currentMax = candidate.dfpsRefreshRates.maxOrNull() ?: candidate.currentHz
    return copy(
        selectedCandidateId = id, targetHz = suggestedTimingTarget(currentMax),
        templateNodeName = null, dfpsReplaceHz = null
    )
}

/**
 * DFPS 面板编辑档位时替换的列表刷新率：用户所选值仍可替换时用它，否则默认替换最高档
 * （开机默认档不可替换）。新增模式返回 null，表示追加到列表末尾。
 */
internal fun MainUiState.effectiveDfpsReplaceHz(candidate: TimingCandidate): Int? {
    if (patchMode != PatchMode.OVERWRITE_EXISTING || !candidate.usesDfps) return null
    val replaceable = candidate.dfpsRefreshRates.filter { it != candidate.currentHz }
    return dfpsReplaceHz?.takeIf { it in replaceable } ?: replaceable.maxOrNull()
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
