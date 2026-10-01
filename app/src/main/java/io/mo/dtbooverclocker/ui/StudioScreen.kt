package io.mo.dtbooverclocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import io.mo.dtbooverclocker.ui.components.IconButton
import io.mo.dtbooverclocker.ui.components.Card
import io.mo.dtbooverclocker.ui.components.TextButton
import io.mo.dtbooverclocker.ui.components.OutlinedButton
import io.mo.dtbooverclocker.ui.components.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.components.appBarScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import androidx.compose.material3.NavigationBarItem as MaterialNavigationBarItem
import io.mo.dtbooverclocker.ui.components.Scaffold
import io.mo.dtbooverclocker.ui.components.TopAppBar
import io.mo.dtbooverclocker.ui.components.AppCard
import io.mo.dtbooverclocker.ui.components.PreferenceIcon
import io.mo.dtbooverclocker.ui.components.FloatingStudioBar
import io.mo.dtbooverclocker.ui.components.LocalFloatingBarInset
import io.mo.dtbooverclocker.ui.components.supportsLiquidGlass
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import io.mo.dtbooverclocker.ui.theme.AppTheme
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
    // Keep the expanded module when temporarily opening theme settings or another app screen.
    pageStateHolder.SaveableStateProvider("studio-controls") {
        StudioScreenContent(state, pagerState, pageStateHolder, navigation, workspace, timing, deviceTree, output)
    }
}

@Composable
private fun StudioScreenContent(
    state: MainUiState,
    pagerState: PagerState,
    pageStateHolder: SaveableStateHolder,
    navigation: NavigationActions,
    workspace: WorkspaceActions,
    timing: TimingActions,
    deviceTree: DeviceTreeActions,
    output: OutputActions
) {
    var activeModule by rememberSaveable { mutableStateOf<StudioModule?>(null) }
    var timingNavigationRequest by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val openTiming: (String) -> Unit = { candidateId ->
        if (!state.busy && state.workspace?.candidates?.any { it.id == candidateId } == true) {
            timing.onSelect(candidateId)
            activeModule = StudioModule.REFRESH_RATE
            timingNavigationRequest++
            scope.launch { pagerState.animateScrollToPage(StudioTab.MODULES.ordinal) }
        }
    }
    StudioNavigation(
        pagerState, pageStateHolder, !state.busy, navigation.onOpenRollback, navigation.onRefreshEnvironment,
        floatingBottomBar = state.floatingBottomBar, liquidGlass = state.liquidGlass
    ) { tab, padding ->
        when (tab) {
            StudioTab.OVERVIEW -> OverviewTab(state, padding, workspace, output, navigation.onOpenRollback, openTiming)
            StudioTab.MODULES -> ModulesTab(state, padding, timing, activeModule, { activeModule = it }, timingNavigationRequest)
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
    floatingBottomBar: Boolean = false,
    liquidGlass: Boolean = false,
    content: @Composable (StudioTab, PaddingValues) -> Unit
) {
    val strings = I18n.current
    val selectedTab = StudioTab.entries[pagerState.currentPage]
    val glassActive = floatingBottomBar && liquidGlass && supportsLiquidGlass()
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val scrollBehavior = key(selectedTab) { MiuixScrollBehavior(canScroll = { enabled }) }
    val selectTab: (StudioTab) -> Unit = { tab ->
        navigationJob?.cancel()
        navigationJob = scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
    }
    Scaffold(
        modifier = Modifier.appBarScroll(scrollBehavior),
        topBar = {
            TopAppBar(
                title = if (AppTheme.isMiuix && selectedTab != StudioTab.OVERVIEW) selectedTab.getLabel(strings) else "DTBO Studio",
                subtitle = if (AppTheme.isMiuix) "" else selectedTab.getLabel(strings),
                scrollBehavior = scrollBehavior,
                titlePadding = Spacing.title,
                actions = {
                    if (selectedTab != StudioTab.SETTINGS) {
                        IconButton(onClick = onOpenRollback, enabled = enabled) { Icon(Icons.Default.Restore, strings.backupAndRestore) }
                        IconButton(onClick = onRefreshEnvironment, enabled = enabled) { Icon(Icons.Default.Refresh, strings.refreshEnvironment) }
                    }
                }
            )
        },
        bottomBar = {
            if (floatingBottomBar) {
                // The floating toolbar is over the scrolling content, outside its captured backdrop.
            } else if (AppTheme.isMiuix) {
                NavigationBar(showDivider = false) {
                    StudioTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectTab(tab) },
                            icon = tab.icon,
                            label = tab.getLabel(strings),
                            enabled = enabled
                        )
                    }
                }
            } else {
                androidx.compose.material3.NavigationBar {
                    StudioTab.entries.forEach { tab ->
                        MaterialNavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectTab(tab) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.getLabel(strings)) },
                            enabled = enabled
                        )
                    }
                }
            }
        },
        floatingToolbar = {
            if (floatingBottomBar) FloatingStudioBar(
                items = StudioTab.entries.map { NavigationItem(label = it.getLabel(strings), icon = it.icon) },
                selectedIndex = selectedTab.ordinal,
                onSelect = { selectTab(StudioTab.entries[it]) },
                enabled = enabled,
                glass = glassActive,
                backdrop = backdrop
            )
        }
    ) { padding ->
        CompositionLocalProvider(LocalFloatingBarInset provides if (floatingBottomBar) 80.dp else 0.dp) {
            // Record the backdrop on a fixed full-size host: the pager's padding changes every frame while the
            // Miuix title collapses, and a layer lagging one frame behind leaves a transparent (black) strip
            // under the floating bar.
            Box(Modifier.fillMaxSize().then(if (glassActive) Modifier.layerBackdrop(backdrop) else Modifier)) {
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
    }
}

@Composable
internal fun OverviewTab(
    state: MainUiState, padding: PaddingValues, workspace: WorkspaceActions, output: OutputActions,
    onOpenRollback: () -> Unit,
    onOpenTiming: ((String) -> Unit)? = null
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("overview-list"),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = if (AppTheme.isMiuix) Spacing.md else Spacing.xs, bottom = Spacing.xl + LocalFloatingBarInset.current),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item(key = "environment") { OverviewEnvironment(state) }
        item(key = "source") { SourceCard(state, workspace.onImport, workspace.onExtract) }
        if (state.workspace != null) {
            item(key = "preview") { RefreshOverviewCard(state, onOpenTiming) }
            item(key = "summary") { ImageSummaryCard(state, onOpenTiming) }
            item(key = "workflow") { WorkflowCard(state) }
            if (state.transactions.isNotEmpty()) item(key = "transactions") {
                TransactionQueueCard(state, workspace.onPackage, workspace.onReset, workspace.onUndoLastTransaction)
            }
            item(key = "output") { OverviewOutputCard(state, output) }
            item(key = "protection") { OverviewProtectionCard(state, onOpenRollback) }
        } else {
            item(key = "workflow") { WorkflowCard(state) }
        }
        state.lastFlash?.let { flash -> item(key = "rescue") { RescueMemoCard(state, output.onCopy, { output.onExportBackup(flash.backupFile) }, { output.onExportRescue(flash.rescueZip) }, output.onScreenshot) } }
        item(key = "terminal") { TerminalCard(state.logs, output.onClearLogs) }
        item(key = "status") { HintText(state.status, Modifier.padding(horizontal = Spacing.xs)) }
    }
}

@Composable
private fun ModulesTab(
    state: MainUiState, padding: PaddingValues, timing: TimingActions,
    activeModule: StudioModule?, onActiveModuleChange: (StudioModule?) -> Unit,
    timingNavigationRequest: Int
) {
    val strings = I18n.current
    val listState = rememberLazyListState()
    var handledTimingRequest by rememberSaveable { mutableIntStateOf(0) }
    val workspace = state.workspace
    LaunchedEffect(timingNavigationRequest, workspace != null) {
        if (timingNavigationRequest > handledTimingRequest && activeModule == StudioModule.REFRESH_RATE &&
            workspace?.candidates?.isNotEmpty() == true) {
            // The scan progress shares the module choices item, so this index stays stable.
            listState.scrollToItem(2)
            handledTimingRequest = timingNavigationRequest
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("modules-list"),
        state = listState,
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = if (AppTheme.isMiuix) Spacing.md else Spacing.xs, bottom = Spacing.xl + LocalFloatingBarInset.current),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Header item is kept even when empty: timing navigation scrolls by item index.
        item {
            if (!AppTheme.isMiuix) {
                Text(strings.modulesTitle, Modifier.padding(horizontal = Spacing.xs), style = MaterialTheme.typography.headlineSmall)
            }
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
            item(key = "module_choices") {
                Column(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = state.capabilityScanInProgress,
                        enter = fadeIn(tween(180)) + expandVertically(
                            tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Top
                        ),
                        exit = fadeOut(tween(180)) + shrinkVertically(
                            tween(300, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top
                        )
                    ) {
                        // Collapse the spacing with the progress bar to leave no empty scan area.
                        Column(Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("capability-scan-progress"))
                            Spacer(Modifier.height(Spacing.md))
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        val refreshFinding = state.capabilityReport?.finding(CapabilityKind.REFRESH_RATE)
                        ModuleCard(strings.moduleRefreshRate, capabilitySubtitle(strings, state, refreshFinding, workspace.candidates.size), Icons.Default.Monitor, !state.busy && (refreshFinding?.matchCount ?: workspace.candidates.size) > 0, activeModule == StudioModule.REFRESH_RATE, Modifier.weight(1f)) { onActiveModuleChange(if (activeModule == StudioModule.REFRESH_RATE) null else StudioModule.REFRESH_RATE) }
                        ModuleCard(strings.moduleCharging, capabilitySubtitle(strings, state, state.capabilityReport?.finding(CapabilityKind.CHARGING), 0), Icons.Default.BatteryChargingFull, !state.busy, activeModule == StudioModule.CHARGING, Modifier.weight(1f)) { onActiveModuleChange(if (activeModule == StudioModule.CHARGING) null else StudioModule.CHARGING) }
                        ModuleCard(strings.moduleAdvancedProps, strings.moduleEditorAlwaysAvailable, Icons.Default.Code, false, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (activeModule == StudioModule.REFRESH_RATE && workspace.candidates.isNotEmpty()) {
                item(key = "timing_panel") {
                    Box(Modifier.testTag("timing-editor")) {
                        key(timingNavigationRequest) {
                            TimingPanel(state, timing.onSelect, timing.onTarget, timing.onStrategy, timing.onPatchMode, timing.onSyncAllDtbEntries, timing.onCustomPixelClock, timing.onCustomVfp, timing.onCustomVbp, timing.onCustomHfp, timing.onCustomHbp, timing.onApplySuggestedCustom, timing.onStageChange, timing.onReportPanelIssue)
                        }
                    }
                }
            }
            if (activeModule == StudioModule.CHARGING) {
                item(key = "charging_panel") { ChargingPanel(state, timing.onStageCharging) }
            }
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
    val miuix = AppTheme.isMiuix
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("settings-hub-list"),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = if (AppTheme.isMiuix) Spacing.md else Spacing.xs, bottom = Spacing.xl + LocalFloatingBarInset.current),
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
        if (miuix) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = strings.backupAndRestore,
                        summary = strings.backupCountSubtitle(state.backups.size),
                        startAction = { PreferenceIcon(Icons.Default.Restore) },
                        onClick = navigation.onOpenRollback
                    )
                    ArrowPreference(
                        title = strings.advancedSettings,
                        summary = strings.advancedSettingsSubtitle,
                        startAction = { PreferenceIcon(Icons.Default.Settings) },
                        onClick = navigation.onOpenAdvancedSettings
                    )
                    ArrowPreference(
                        title = strings.aboutStudio,
                        summary = strings.aboutStudioSubtitle,
                        startAction = { PreferenceIcon(Icons.Default.Info) },
                        onClick = navigation.onOpenAbout
                    )
                }
            }
        } else {
            item { NavigationEntry(Icons.Default.Restore, strings.backupAndRestore, strings.backupCountSubtitle(state.backups.size), navigation.onOpenRollback) }
            item { NavigationEntry(Icons.Default.Settings, strings.advancedSettings, strings.advancedSettingsSubtitle, navigation.onOpenAdvancedSettings) }
            item { NavigationEntry(Icons.Default.Info, strings.aboutStudio, strings.aboutStudioSubtitle, navigation.onOpenAbout) }
        }
    }
}
