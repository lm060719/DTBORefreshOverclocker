package io.mo.dtbooverclocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransactionKind
import io.mo.dtbooverclocker.model.AvbProtectionState
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.SourceMode
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.*
import io.mo.dtbooverclocker.ui.components.Button
import io.mo.dtbooverclocker.ui.components.TextButton
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing
import java.util.Locale

/** Read theme colors during composition, so palette changes also redraw the Canvas charts. */
@Composable
private fun DashboardCard(
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(containerColor = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                trailing?.invoke(this)
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewEnvironment(state: MainUiState) {
    val strings = I18n.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HintText(if (state.workspace != null) strings.workspaceLoaded else strings.workflowSubtitle,
            Modifier.padding(horizontal = Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatusPill(if (state.rootState.granted) strings.rootGrantedPill else strings.nonRootAvailable,
                tone = if (state.rootState.granted) Tone.Success else Tone.Neutral,
                icon = if (state.rootState.granted) Icons.Default.CheckCircle else Icons.Default.Lock)
            state.slotInfo?.let { StatusPill(it.label, icon = Icons.Default.Memory) }
            state.workspace?.let { StatusPill("DTBO v${it.metadata.version}") }
        }
    }
}

@Composable
internal fun SourceCard(state: MainUiState, onImport: () -> Unit, onExtract: () -> Unit) {
    val strings = I18n.current
    val workspace = state.workspace
    val scheme = MaterialTheme.colorScheme
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.padding(Spacing.md).size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(workspace?.inputImage?.name ?: strings.waitingForImage,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                HintText(if (workspace == null) strings.imageSource
                    else if ((workspace.sourceImage?.sourceMode ?: state.sourceMode) == SourceMode.ROOT_PARTITION)
                        strings.overviewPartitionSource else strings.overviewLocalSource)
            }
            if (workspace != null) TextButton(onImport, enabled = !state.busy) { Text(strings.overviewChangeImage) }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        FlowActions {
            TextButton(onImport, Modifier.weight(1f).testTag("overview-import"), enabled = !state.busy) {
                IconLabel(Icons.Default.FolderOpen, strings.manualImport)
            }
            TextButton(onExtract, Modifier.weight(1f).testTag("overview-extract"),
                enabled = state.rootState.suPresent && !state.busy) {
                IconLabel(Icons.Default.Save, strings.extractCurrentPartition)
            }
        }
        if (workspace == null) HintText(if (state.rootState.suPresent)
            strings.hintRootExtract(state.slotInfo?.blockDevice ?: "dtbo") else strings.hintNoRootImport)
    }
}

/** Buttons wrap at large font scales rather than squeezing labels into one row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
}

@Composable
internal fun RefreshOverviewCard(state: MainUiState, onOpenTiming: ((String) -> Unit)? = null) {
    val workspace = state.workspace ?: return
    val strings = I18n.current
    val change = state.stagedChanges.lastOrNull()
    val candidate = workspace.candidates.firstOrNull { it.id == state.selectedCandidateId }
    if (change == null && candidate == null) {
        DashboardCard(strings.overviewRefreshPreview) { HintText(strings.overviewNoTiming) }
        return
    }
    // Use the committed transaction, never the un-staged target field in the editor.
    val original = change?.originalHz ?: candidate!!.currentHz
    val target = change?.targetHz
    val deleted = change?.mode == PatchMode.DELETE_EXISTING
    val path = change?.nodePath ?: candidate!!.nodePath
    val entry = change?.entryIndex ?: candidate!!.entryIndex
    val panel = TimingUtils.formatPanelDisplayName(TimingUtils.parsePanelIdentifier(path))
    val scheme = MaterialTheme.colorScheme
    val delta = if (target != null && original > 0 && !deleted) (target - original) * 100.0 / original else null
    DashboardCard(strings.overviewRefreshPreview,
        trailing = {
            if (change != null) StatusPill(if (state.patchReport == null) strings.stage else strings.overviewPackaged,
                tone = if (state.patchReport == null) Tone.Warning else Tone.Primary)
        },
        container = lerp(scheme.surfaceContainerLow, scheme.primaryContainer, 0.65f)) {
        OverviewTimingLink(strings.overviewTimingLocation(panel, entry),
            state.overviewTimingCandidate(TimingUtils.parsePanelIdentifier(path), entry, path), state.busy,
            onOpenTiming, Modifier.testTag("overview-preview-panel"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TimingValue(original.toString(), strings.overviewOriginalTiming, Modifier.weight(1f), scheme.onSurface)
            if (change != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    delta?.let {
                        Text("${if (it >= 0) "+" else ""}${String.format(Locale.getDefault(), "%.0f", it)}%",
                            color = scheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                TimingValue(if (deleted) strings.delete else target.toString(),
                    if (deleted) change.mode.getDisplayName(strings) else strings.overviewStagedTarget,
                    Modifier.weight(1f), scheme.primary, showUnit = !deleted)
            }
        }
        if (!deleted && target != null && original > 0 && target > 0) {
            Text(strings.overviewFrameInterval, style = MaterialTheme.typography.labelLarge)
            val originalMs = 1000f / original
            val targetMs = 1000f / target
            val scale = maxOf(originalMs, targetMs)
            FrameIntervalRow(strings.overviewOriginalTiming, originalMs, originalMs / scale,
                lerp(scheme.primary, scheme.surfaceContainerHighest, 0.5f), "overview-original-interval")
            FrameIntervalRow(strings.overviewStagedTarget, targetMs, targetMs / scale, scheme.primary, "overview-target-interval")
        }
        if (change?.mode == PatchMode.APPEND_NEW) HintText(change.mode.getDescription(strings))
        HintText(when {
            change == null -> strings.overviewOriginalTimingHint
            state.lastFlash != null && state.patchReport != null -> strings.overviewFlashedTimingHint
            state.patchReport != null -> strings.overviewPackagedTimingHint
            else -> strings.overviewPendingTimingHint
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimingValue(value: String, label: String, modifier: Modifier, color: Color, showUnit: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
            if (showUnit) Text("Hz", Modifier.align(Alignment.Bottom), color = color, style = MaterialTheme.typography.labelLarge)
        }
        HintText(label)
    }
}

@Composable
private fun FrameIntervalRow(label: String, ms: Float, fraction: Float, color: Color, tag: String) {
    val value = "${String.format(Locale.getDefault(), "%.2f", ms)} ms"
    val fontScale = LocalDensity.current.fontScale
    val track: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(color, CircleShape).testTag(tag))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 300.dp && fontScale <= 1.3f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall)
                track(Modifier.weight(1f))
                Text(value, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.labelMedium)
            }
            track(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ImageSummaryCard(state: MainUiState, onOpenTiming: ((String) -> Unit)? = null) {
    val workspace = state.workspace ?: return
    val strings = I18n.current
    val groups = remember(workspace.candidates) { TimingUtils.groupCandidates(workspace.candidates) }
    val counts = remember(groups) { PanelClassification.entries.map { classification -> groups.keys.count { it.classification == classification } } }
    val labels = listOf(strings.overviewVendor, strings.overviewReference, strings.overviewSimulation, strings.overviewUnknown)
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, lerp(scheme.primary, scheme.onSurfaceVariant, 0.4f), scheme.onSurfaceVariant, scheme.outlineVariant)
    var expanded by rememberSaveable(workspace.inputImage.absolutePath) { mutableStateOf(false) }
    DashboardCard(strings.imageParseResult) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MetricTile(strings.dtbCount, workspace.metadata.entries.size, Modifier.weight(1f))
            MetricTile(strings.uniquePanels, groups.size, Modifier.weight(1f))
            MetricTile(strings.timingCandidates, workspace.candidates.size, Modifier.weight(1f))
        }
        if (groups.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().testTag("overview-panel-chart")) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    val diameter = size.minDimension - stroke
                    var start = -90f
                    counts.forEachIndexed { index, count ->
                        val sweep = 360f * count / groups.size
                        if (count > 0) drawArc(colors[index], start, sweep, false,
                            Offset(inset, inset), Size(diameter, diameter), style = Stroke(stroke))
                        start += sweep
                    }
                }
                Text(groups.size.toString(), style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                labels.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box(Modifier.size(8.dp).background(colors[index], CircleShape))
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(counts[index].toString(), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        state.activePanelDisplayName?.let {
            val identifier = state.activePanelIdentifier
                ?: groups.keys.singleOrNull { group -> group.panelDisplayName == it }?.panelIdentifier
            OverviewTimingLink(strings.panelInUse(it), identifier?.let { id -> state.overviewTimingCandidate(id) }, state.busy,
                onOpenTiming, Modifier.testTag("overview-active-panel"), Icons.Default.CheckCircle)
        }
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth().testTag("overview-summary-details")) {
            IconLabel(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) strings.collapse else strings.overviewDetails)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val instances = remember(workspace.candidates) {
                    workspace.candidates.map { it.entryIndex to TimingUtils.parsePanelIdentifier(it.nodePath) }.distinct().size
                }
                HintText(strings.panelDtbInstances(instances))
                if (onOpenTiming != null) groups.keys.forEach { group ->
                    val target = state.overviewTimingCandidate(group.panelIdentifier)
                    OverviewTimingLink(target?.let { strings.overviewTimingLocation(group.panelDisplayName, it.entryIndex) }
                        ?: group.panelDisplayName, target, state.busy, onOpenTiming,
                        Modifier.testTag("overview-panel-${group.panelIdentifier}"))
                }
            }
        }
    }
}

@Composable
private fun OverviewTimingLink(
    label: String, candidate: TimingCandidate?, busy: Boolean, onOpenTiming: ((String) -> Unit)?,
    modifier: Modifier = Modifier, icon: ImageVector? = null
) {
    val strings = I18n.current
    val canOpen = candidate != null && onOpenTiming != null
    Row(modifier.fillMaxWidth()
        .then(if (onOpenTiming != null) Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .clickable(enabled = canOpen && !busy, role = Role.Button, onClickLabel = strings.overviewEditTiming) {
                candidate?.let { onOpenTiming(it.id) }
            } else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (icon != null) Icon(icon, null, tint = AppTheme.status.success, modifier = Modifier.size(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (canOpen && !busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (canOpen) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MetricTile(label: String, value: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WorkflowCard(state: MainUiState) {
    val strings = I18n.current
    val scheme = MaterialTheme.colorScheme
    val labels = listOf(strings.stepImport, strings.stepAnalyze, strings.stepEdit, strings.stepPackage, strings.output)
    // Output is an available stage, not a claim that a file was saved (or a device booted).
    val current = when {
        state.workspace == null -> 0
        state.capabilityScanInProgress || state.capabilityReport == null -> 1
        state.transactions.isEmpty() -> 2
        state.patchReport == null -> 3
        else -> 4
    }
    DashboardCard(strings.overviewProgress) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val cell = size.width / labels.size
                for (index in 0 until labels.lastIndex) drawLine(
                    if (index < current) scheme.primary else scheme.outlineVariant,
                    Offset(cell * (index + 0.5f), size.height / 2),
                    Offset(cell * (index + 1.5f), size.height / 2), 2.dp.toPx())
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                labels.forEachIndexed { index, label ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Surface(Modifier.size(28.dp), CircleShape,
                            color = when { index < current -> scheme.primary; index == current -> scheme.primaryContainer; else -> scheme.surfaceContainerHighest },
                            contentColor = when { index < current -> scheme.onPrimary; index == current -> scheme.onPrimaryContainer; else -> scheme.onSurfaceVariant }) {
                            Box(contentAlignment = Alignment.Center) {
                                if (index < current) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                else Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = if (index == current) scheme.primary else scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TransactionQueueCard(state: MainUiState, onPackage: () -> Unit, onReset: () -> Unit, onUndoLastTransaction: () -> Unit) {
    val strings = I18n.current
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val packaged = state.patchReport != null
    val total = state.workspace?.metadata?.entries?.size ?: 0
    val scope = strings.overviewScope(state.modifiedEntryIndices.size, total, state.transactions.sumOf { it.operationCount })
    DashboardCard(if (packaged) strings.overviewPackagedChanges else strings.overviewPendingChanges,
        trailing = { StatusPill(strings.overviewTransactionCount(state.transactions.size), tone = Tone.Primary) }) {
        HintText(scope)
        Text(strings.overviewChangeScope, style = MaterialTheme.typography.labelLarge)
        val entries = state.workspace?.metadata?.entries.orEmpty()
        val modified = state.modifiedEntryIndices
        Canvas(Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = scope }) {
            if (entries.isNotEmpty()) {
                val cell = size.width / entries.size
                val gap = minOf(2.dp.toPx(), cell / 4)
                entries.forEachIndexed { position, entry ->
                    drawRoundRect(if (entry.index in modified) scheme.primary else scheme.surfaceContainerHighest,
                        topLeft = Offset(position * cell, 0f), size = Size(cell - gap, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
        }
        val visible = if (expanded) state.transactions.asReversed() else state.transactions.takeLast(3).asReversed()
        visible.forEach { transaction ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(when (transaction.kind) {
                    DeviceTreeTransactionKind.REFRESH_RATE -> Icons.Default.Speed
                    DeviceTreeTransactionKind.CHARGING -> Icons.Default.BatteryChargingFull
                    DeviceTreeTransactionKind.GENERIC_EDIT -> Icons.Default.AccountTree
                }, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(transaction.timingChange?.mode?.getDisplayName(strings) ?: transaction.kind.getDisplayName(strings),
                        style = MaterialTheme.typography.titleSmall)
                    Text(transaction.summary, style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis)
                    if (expanded) transaction.warnings.forEach { NoticeBanner(it, tone = Tone.Warning) }
                }
                Icon(if (packaged) Icons.Default.CheckCircle else Icons.Default.Schedule, null,
                    tint = if (packaged) scheme.primary else AppTheme.status.warning, modifier = Modifier.size(16.dp))
            }
        }
        ActionRow {
            TextButton(onUndoLastTransaction, enabled = !state.busy) { Text(strings.undoLastTransaction) }
            TextButton({ expanded = !expanded }) { Text(if (expanded) strings.collapse else strings.overviewDetails) }
            TextButton(onReset, enabled = !state.busy,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = scheme.error)) { Text(strings.resetAll) }
        }
        if (!packaged) Button(onPackage, Modifier.fillMaxWidth().testTag("overview-package"), enabled = !state.busy) {
            IconLabel(Icons.Default.Build, strings.overviewPackageAction)
        }
    }
}

@Composable
internal fun OverviewOutputCard(state: MainUiState, output: OutputActions) {
    val strings = I18n.current
    val report = state.patchReport
    DashboardCard(strings.overviewOutputFormats,
        trailing = { if (report == null) Text(strings.overviewOutputPending, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
        FlowActions {
            OutputChoice(strings.overviewImageFormat, Icons.AutoMirrored.Filled.InsertDriveFile, report != null && !state.busy,
                Modifier.weight(1f), { report?.let { output.onSavePatched(it.outputImage) } })
            OutputChoice(strings.overviewRecoveryFormat, Icons.Default.Inventory2,
                report != null && state.slotInfo != null && !state.busy, Modifier.weight(1f), output.onRecoveryZip)
        }
        FlowActions {
            OutputChoice(strings.overviewFastbootFormat, Icons.Default.Terminal,
                report != null && state.slotInfo != null && !state.busy, Modifier.weight(1f), output.onFastbootBundle)
            OutputChoice(strings.overviewModuleFormat, Icons.Default.Extension, report != null && !state.busy,
                Modifier.weight(1f), output.onModuleZip)
        }
        if (report != null) {
            HintText(report.outputImage.name)
            var expanded by rememberSaveable(report.outputImage.absolutePath) { mutableStateOf(false) }
            if (report.changes.isNotEmpty()) {
                TextButton({ expanded = !expanded }) { Text(if (expanded) strings.collapse else strings.overviewDetails) }
                AnimatedVisibility(expanded) { Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    report.changes.forEach { HintText(it) }
                } }
            }
            report.warnings.forEach { NoticeBanner(it, tone = Tone.Danger) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(strings.flashToDevice, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            val canFlash = state.rootState.granted && state.slotInfo != null && state.transactions.isNotEmpty() && !state.busy
            Button(output.onFlash, Modifier.fillMaxWidth(), enabled = canFlash, colors = dangerButtonColors()) {
                IconLabel(Icons.Default.FlashOn, strings.directFlashPartition)
            }
            Button(output.onFlashModule, Modifier.fillMaxWidth(), enabled = canFlash, colors = dangerButtonColors()) {
                IconLabel(Icons.Default.FlashOn, strings.makeModuleAndFlash)
            }
            HintText(strings.moduleFlashHint)
            if (!state.rootState.granted) HintText(strings.directFlashRequiresRoot)
        }
    }
}

@Composable
private fun OutputChoice(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier, enabled = enabled) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun OverviewProtectionCard(state: MainUiState, onOpenRollback: () -> Unit) {
    val workspace = state.workspace ?: return
    val strings = I18n.current
    DashboardCard(strings.overviewProtection) {
        workspace.sourceImage?.let { source ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(when (source.avbProtectionState) {
                    AvbProtectionState.NONE -> strings.avbNone
                    AvbProtectionState.UNSIGNED -> strings.avbUnsigned
                    AvbProtectionState.SIGNED -> strings.avbSigned(source.avbAlgorithm)
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (source.avbProtectionState == AvbProtectionState.SIGNED) HintText(strings.overviewAvbHint)
        }
        TextButton(onOpenRollback, Modifier.fillMaxWidth(), enabled = !state.busy) {
            IconLabel(Icons.Default.Restore, strings.backupCountSubtitle(state.backups.size))
        }
    }
}
