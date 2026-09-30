package io.mo.dtbooverclocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import io.mo.dtbooverclocker.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import io.mo.dtbooverclocker.ui.components.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.mo.dtbooverclocker.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.components.HintText
import io.mo.dtbooverclocker.ui.components.IconLabel
import io.mo.dtbooverclocker.ui.components.KeyValueRow
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.Tone
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.Spacing

@Composable
internal fun RescueMemoCard(
    state: MainUiState,
    onCopy: (String) -> Unit,
    onExportBackup: () -> Unit,
    onExportRescue: () -> Unit,
    onScreenshot: () -> Unit
) {
    val flash = state.lastFlash ?: return
    val strings = I18n.current
    SectionCard(title = strings.rescueMemo, icon = Icons.Default.Warning, tone = Tone.Danger) {
        KeyValueRow(strings.flashedPartition, flash.flashedPartition, monospace = true)
        KeyValueRow(strings.backupSha256, flash.backupSha256, monospace = true)
        flash.backupExternalUri?.let { KeyValueRow(strings.backupLocation, it) }
        flash.rescueExternalUri?.let { KeyValueRow(strings.rescueZipLocation, it) }

        flash.rollbackCommands.forEach { command ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        command,
                        Modifier.weight(1f).padding(vertical = Spacing.md),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = { onCopy(command) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = strings.copy)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) {
                Text(strings.exportBackup)
            }
            OutlinedButton(onClick = onExportRescue, modifier = Modifier.weight(1f)) {
                Text(strings.exportRescue)
            }
        }
        OutlinedButton(onClick = onScreenshot, modifier = Modifier.fillMaxWidth()) {
            IconLabel(Icons.Default.Image, strings.saveScreenshotMemo)
        }
    }
}

/** 终端回显：默认折叠只显示最后一行，展开后显示完整滚动日志。 */
@Composable
internal fun TerminalCard(logs: List<String>, onClear: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val strings = I18n.current
    LaunchedEffect(logs.size, expanded) {
        if (expanded && logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    SectionCard(
        title = strings.terminalEcho,
        subtitle = strings.linesCount(logs.size),
        icon = Icons.Default.Terminal,
        modifier = Modifier.clickable { expanded = !expanded },
        trailing = {
            TextButton(onClick = onClear) { Text(strings.clear) }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) strings.collapse else strings.expand,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        if (!expanded) {
            logs.lastOrNull()?.let { last ->
                Text(
                    last,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
                        .padding(Spacing.md),
                    state = listState
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

