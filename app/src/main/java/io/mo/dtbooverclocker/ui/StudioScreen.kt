package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.CapabilityFinding
import io.mo.dtbooverclocker.model.CapabilityKind
import io.mo.dtbooverclocker.model.CapabilityStatus
import androidx.compose.foundation.BorderStroke
import io.mo.dtbooverclocker.ui.components.ActionRow
import io.mo.dtbooverclocker.ui.components.EmptyState
import io.mo.dtbooverclocker.ui.components.HintText
import io.mo.dtbooverclocker.ui.components.IconLabel
import io.mo.dtbooverclocker.ui.components.KeyValueRow
import io.mo.dtbooverclocker.ui.components.NavigationEntry
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.StatusPill
import io.mo.dtbooverclocker.ui.components.Tone
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import io.mo.dtbooverclocker.ui.i18n.AppStrings
import io.mo.dtbooverclocker.ui.i18n.I18n

enum class StudioTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    MODULES("功能模块", Icons.Default.Apps),
    DEVICE_TREE("设备树", Icons.Default.AccountTree),
    SETTINGS("设置", Icons.Default.Settings);

    fun getLabel(strings: AppStrings): String = when (this) {
        OVERVIEW -> strings.tabOverview
        MODULES -> strings.tabModules
        DEVICE_TREE -> strings.tabDeviceTree
        SETTINGS -> strings.tabSettings
    }
}

private enum class StudioModule { REFRESH_RATE, CHARGING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    state: MainUiState,
    pagerState: PagerState,
    pageStateHolder: SaveableStateHolder,
    navigation: NavigationActions,
    workspace: WorkspaceActions,
    timing: TimingActions,
    deviceTree: DeviceTreeActions,
    output: OutputActions
) {
    StudioNavigation(pagerState, pageStateHolder, !state.busy, navigation.onOpenRollback, navigation.onRefreshEnvironment) { tab, padding ->
        when (tab) {
            StudioTab.OVERVIEW -> OverviewTab(state, padding, workspace, output)
            StudioTab.MODULES -> ModulesTab(state, padding, timing)
            StudioTab.DEVICE_TREE -> DeviceTreeScreen(
                state = state,
                contentPadding = padding,
                onSetProperty = deviceTree.onSetProperty,
                onAddProperty = deviceTree.onAddProperty,
                onDeleteProperty = deviceTree.onDeleteProperty,
                onAddNode = deviceTree.onAddNode,
                onCloneNode = deviceTree.onCloneNode,
                onRenameNode = deviceTree.onRenameNode,
                onDeleteNode = deviceTree.onDeleteNode,
                onUndoThroughTransaction = workspace.onUndoThroughTransaction
            )
            StudioTab.SETTINGS -> SettingsHubTab(state, padding, navigation)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioNavigation(
    pagerState: PagerState,
    pageStateHolder: SaveableStateHolder,
    enabled: Boolean,
    onOpenRollback: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    content: @Composable (StudioTab, PaddingValues) -> Unit
) {
    val strings = I18n.current
    val selectedTab = StudioTab.entries[pagerState.currentPage]
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("DTBO Studio", fontWeight = FontWeight.SemiBold); Text(selectedTab.getLabel(strings), style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    if (selectedTab != StudioTab.SETTINGS) {
                        IconButton(onClick = onOpenRollback, enabled = enabled) { Icon(Icons.Default.Restore, strings.backupAndRestore) }
                        IconButton(onClick = onRefreshEnvironment, enabled = enabled) { Icon(Icons.Default.Refresh, strings.refreshEnvironment) }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar { StudioTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = {
                        navigationJob?.cancel()
                        navigationJob = scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                    },
                    icon = { Icon(tab.icon, null) },
                    label = { Text(tab.getLabel(strings)) },
                    enabled = enabled
                )
            } }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            key = { StudioTab.entries[it].name },
            userScrollEnabled = enabled
        ) { page ->
            val tab = StudioTab.entries[page]
            pageStateHolder.SaveableStateProvider(tab.name) {
                content(tab, PaddingValues())
            }
        }
    }
}

@Composable
private fun OverviewTab(
    state: MainUiState, padding: PaddingValues, workspace: WorkspaceActions, output: OutputActions
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item(key = "hero") { WorkflowCard(state) }
        item(key = "source") { SourceCard(state, workspace.onImport, workspace.onExtract) }
        if (state.workspace != null) {
            item(key = "summary") { ImageSummaryCard(state) }
            if (state.transactions.isNotEmpty()) item(key = "transactions") {
                TransactionQueueCard(state, workspace.onPackage, workspace.onReset, workspace.onUndoLastTransaction)
            }
        }
        state.patchReport?.let { report -> item(key = "output") { OutputCard(state, { output.onSavePatched(report.outputImage) }, output.onRecoveryZip, output.onFastbootBundle, output.onModuleZip, output.onFlash, output.onFlashModule) } }
        state.lastFlash?.let { flash -> item(key = "rescue") { RescueMemoCard(state, output.onCopy, { output.onExportBackup(flash.backupFile) }, { output.onExportRescue(flash.rescueZip) }, output.onScreenshot) } }
        item(key = "terminal") { TerminalCard(state.logs, output.onClearLogs) }
        item(key = "status") { HintText(state.status, Modifier.padding(horizontal = Spacing.xs)) }
    }
}

@Composable
private fun TransactionQueueCard(
    state: MainUiState,
    onPackage: () -> Unit,
    onReset: () -> Unit,
    onUndoLastTransaction: () -> Unit
) {
    val strings = I18n.current
    SectionCard(
        title = strings.dtTransactions(state.transactions.size),
        subtitle = strings.dtOperationsPending(state.transactions.sumOf { it.operationCount }),
        icon = Icons.Default.PendingActions,
        tone = Tone.Primary
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            state.transactions.takeLast(5).forEach { transaction ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(
                            "${transaction.kind.displayName} · ${transaction.risk.displayName} · ${transaction.operationCount} ops",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(transaction.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = onPackage, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            IconLabel(Icons.Default.Build, strings.packageBatch)
        }
        ActionRow {
            OutlinedButton(onClick = onUndoLastTransaction, enabled = !state.busy) { Text(strings.undoLastTransaction) }
            TextButton(onClick = onReset, enabled = !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.resetAll) }
        }
    }
}

@Composable
private fun ModulesTab(
    state: MainUiState, padding: PaddingValues, timing: TimingActions
) {
    val strings = I18n.current
    var activeModule by rememberSaveable { mutableStateOf<StudioModule?>(null) }
    val workspace = state.workspace
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column(Modifier.padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(strings.modulesTitle, style = MaterialTheme.typography.headlineSmall)
                HintText(strings.modulesSubtitle)
            }
        }
        if (workspace != null) {
            item { CapabilityScanCard(state) }
        }
        if (workspace == null) {
            item {
                EmptyState(
                    Icons.Default.FolderOpen,
                    strings.noWorkspaceYet,
                    strings.noWorkspaceHint
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val refreshFinding = state.capabilityReport?.finding(CapabilityKind.REFRESH_RATE)
                    ModuleCard(strings.moduleRefreshRate, capabilitySubtitle(strings, state, refreshFinding, workspace.candidates.size), Icons.Default.Monitor, (refreshFinding?.matchCount ?: workspace.candidates.size) > 0, activeModule == StudioModule.REFRESH_RATE, Modifier.weight(1f)) { activeModule = if (activeModule == StudioModule.REFRESH_RATE) null else StudioModule.REFRESH_RATE }
                    ModuleCard(strings.moduleCharging, capabilitySubtitle(strings, state, state.capabilityReport?.finding(CapabilityKind.CHARGING), 0), Icons.Default.BatteryChargingFull, !state.busy, activeModule == StudioModule.CHARGING, Modifier.weight(1f)) { activeModule = if (activeModule == StudioModule.CHARGING) null else StudioModule.CHARGING }
                    ModuleCard(strings.moduleAdvancedProps, strings.moduleEditorAlwaysAvailable, Icons.Default.Code, false, modifier = Modifier.weight(1f))
                }
            }
            if (activeModule == StudioModule.REFRESH_RATE && workspace.candidates.isNotEmpty()) {
                item { TimingPanel(state, timing.onSelect, timing.onTarget, timing.onStrategy, timing.onPatchMode, timing.onCustomPixelClock, timing.onCustomVfp, timing.onCustomVbp, timing.onCustomHfp, timing.onCustomHbp, timing.onApplySuggestedCustom, timing.onStageChange) }
            }
            if (activeModule == StudioModule.CHARGING) {
                item(key = "charging_panel") { ChargingPanel(state, timing.onStageCharging) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityScanCard(state: MainUiState) {
    val strings = I18n.current
    val report = state.capabilityReport
    val (statusText, statusTone) = when {
        state.capabilityScanInProgress -> strings.scanning to Tone.Warning
        report != null -> strings.scanCompleted to Tone.Success
        else -> strings.waitingForScan to Tone.Neutral
    }
    SectionCard(
        title = strings.capabilityScan,
        subtitle = report?.let { strings.scanStats(it.scannedEntryCount, it.nodeCount, it.propertyCount) },
        icon = Icons.Default.Radar,
        trailing = { StatusPill(statusText, tone = statusTone) }
    ) {
        if (state.capabilityScanInProgress) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (report != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                report.findings.forEach { finding ->
                    StatusPill(
                        "${finding.kind.getDisplayName(strings)}: ${finding.status.getDisplayName(strings)} ${finding.matchCount}",
                        tone = when (finding.status) {
                            CapabilityStatus.AVAILABLE -> Tone.Success
                            CapabilityStatus.ANALYSIS_ONLY -> Tone.Primary
                            CapabilityStatus.NOT_FOUND -> Tone.Neutral
                        }
                    )
                }
            }
        } else {
            HintText(strings.scanHint)
        }
    }
}

private fun capabilitySubtitle(
    strings: AppStrings,
    state: MainUiState,
    finding: CapabilityFinding?,
    fallbackCount: Int
): String
{
    if (state.capabilityScanInProgress && finding == null)
    {
        return strings.scanning
    }
    if (finding == null)
    {
        return if (fallbackCount > 0) strings.candidatesCount(fallbackCount) else strings.waitingForScan
    }
    return when (finding.status)
    {
        CapabilityStatus.AVAILABLE -> strings.statusAvailable(finding.matchCount)
        CapabilityStatus.ANALYSIS_ONLY -> strings.statusAnalysisOnly(finding.matchCount)
        CapabilityStatus.NOT_FOUND -> strings.statusNotFound
    }
}

@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        border = if (active) BorderStroke(2.dp, scheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = when {
                active -> scheme.primaryContainer
                enabled -> scheme.surfaceContainerLow
                else -> scheme.surfaceContainerLow.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (enabled || active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.6f))
                Spacer(Modifier.weight(1f))
                if (active) Icon(Icons.Default.CheckCircle, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled || active) scheme.onSurface else scheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsHubTab(state: MainUiState, padding: PaddingValues, navigation: NavigationActions) {
    val strings = I18n.current
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            SectionCard(
                title = strings.envStatus,
                icon = Icons.Default.PhoneAndroid,
                trailing = {
                    if (state.rootState.granted) StatusPill(strings.rootGrantedPill, tone = Tone.Success)
                    else StatusPill(strings.rootNotGrantedPill, tone = Tone.Warning)
                }
            ) {
                if (!state.rootState.granted) HintText(state.rootState.detail)
                state.slotInfo?.let { KeyValueRow(strings.currentSlot, it.label) }
                KeyValueRow(strings.dtboPartition, state.slotInfo?.blockDevice ?: strings.detectingPartition, monospace = true)
                ActionRow {
                    if (!state.rootState.granted) OutlinedButton(navigation.onRequestRoot, enabled = state.rootState.suPresent) { IconLabel(Icons.Default.Lock, strings.requestRoot) }
                    OutlinedButton(navigation.onRefreshEnvironment) { IconLabel(Icons.Default.Refresh, strings.reprobe) }
                }
            }
        }
        item { NavigationEntry(Icons.Default.Restore, strings.backupAndRestore, strings.backupCountSubtitle(state.backups.size), navigation.onOpenRollback) }
        item { NavigationEntry(Icons.Default.Settings, strings.advancedSettings, strings.advancedSettingsSubtitle, navigation.onOpenAdvancedSettings) }
        item { NavigationEntry(Icons.Default.Info, strings.aboutStudio, strings.aboutStudioSubtitle, navigation.onOpenAbout) }
    }
}
