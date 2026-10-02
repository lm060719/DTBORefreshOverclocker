package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import io.mo.dtbooverclocker.core.TimingDeletionPlanner
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.Button
import io.mo.dtbooverclocker.ui.components.IconLabel
import io.mo.dtbooverclocker.ui.components.NoticeBanner
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.TextButton
import io.mo.dtbooverclocker.ui.components.Tone
import io.mo.dtbooverclocker.ui.components.dangerButtonColors
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.Spacing

@Composable
internal fun TimingDeletionSelector(
    candidates: List<TimingCandidate>,
    anchor: TimingCandidate,
    busy: Boolean,
    sync: Boolean,
    onDelete: (Set<String>) -> Unit
) {
    val strings = I18n.current
    val panel = TimingDeletionPlanner.panelCandidates(candidates, anchor)
    val selectionKey = panel.map { it.id }
    var selectedIds by remember(selectionKey) {
        mutableStateOf(if (TimingDeletionPlanner.canDelete(panel, listOf(anchor))) setOf(anchor.id) else emptySet())
    }
    var showConfirm by remember(selectionKey) { mutableStateOf(false) }
    val selected = panel.filter { it.id in selectedIds }
    val canDelete = TimingDeletionPlanner.canDelete(panel, selected)

    SectionCard(title = strings.deleteTimingCandidateTitle, icon = Icons.Default.Warning, tone = Tone.Danger) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(strings.selectTimingDeletionHint, style = MaterialTheme.typography.bodySmall)
            panel.forEach { candidate ->
                val checked = candidate.id in selectedIds
                val enabled = !busy && (checked || TimingDeletionPlanner.canDelete(panel, selected + candidate))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("timing-delete-${candidate.id}")
                        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) {
                            selectedIds = if (checked) selectedIds - candidate.id else selectedIds + candidate.id
                        }.padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                        Text("${candidate.currentHz} Hz", style = MaterialTheme.typography.bodyMedium)
                        Text(TimingUtils.parseTimingNodeName(candidate.nodePath),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(strings.selectedTimingDeletionCount(selected.size, panel.size - selected.size),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("timing-delete-count"))
            if (panel.any { !TimingDeletionPlanner.canDelete(panel, listOf(it)) }) {
                NoticeBanner(strings.deleteOnlyModeWarning, tone = Tone.Danger)
            }
            if (sync) Text(strings.deleteSyncHint, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selectedIds = emptySet() }, enabled = selectedIds.isNotEmpty() && !busy) {
                Text(strings.clear)
            }
        }
    }
    Button(onClick = { showConfirm = true }, enabled = canDelete && !busy,
        colors = dangerButtonColors(), modifier = Modifier.fillMaxWidth().testTag("timing-delete-submit")) {
        IconLabel(Icons.Default.Delete, strings.deleteSelectedTimingsBtn(selected.size))
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(strings.confirmSelectedTimingDeletion(selected.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    selected.forEach {
                        Text(strings.deleteNodeLabel(TimingUtils.parseTimingNodeName(it.nodePath), it.currentHz))
                    }
                    Text(strings.deleteModeRetainHint(panel.size - selected.size))
                    if (sync) Text(strings.deleteSyncHint)
                }
            },
            confirmButton = {
                Button(onClick = { showConfirm = false; onDelete(selectedIds) }, enabled = canDelete && !busy,
                    colors = dangerButtonColors(), modifier = Modifier.testTag("timing-delete-confirm")) {
                    Text(strings.deleteSelectedTimingsBtn(selected.size))
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text(strings.cancel) } }
        )
    }
}
