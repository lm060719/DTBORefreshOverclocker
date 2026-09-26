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

enum class StudioTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    MODULES("功能模块", Icons.Default.Apps),
    DEVICE_TREE("设备树", Icons.Default.AccountTree),
    SETTINGS("设置", Icons.Default.Settings)
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
    val selectedTab = StudioTab.entries[pagerState.currentPage]
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("DTBO Studio", fontWeight = FontWeight.SemiBold); Text(selectedTab.label, style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    if (selectedTab != StudioTab.SETTINGS) {
                        IconButton(onClick = onOpenRollback, enabled = enabled) { Icon(Icons.Default.Restore, "备份与恢复") }
                        IconButton(onClick = onRefreshEnvironment, enabled = enabled) { Icon(Icons.Default.Refresh, "刷新环境") }
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
                    label = { Text(tab.label) },
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
    SectionCard(
        title = "设备树事务 · ${state.transactions.size} 个",
        subtitle = "${state.transactions.sumOf { it.operationCount }} 个底层操作待打包",
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
            IconLabel(Icons.Default.Build, "集中打包")
        }
        ActionRow {
            OutlinedButton(onClick = onUndoLastTransaction, enabled = !state.busy) { Text("撤销最近事务") }
            TextButton(onClick = onReset, enabled = !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("全部重置") }
        }
    }
}

@Composable
private fun ModulesTab(
    state: MainUiState, padding: PaddingValues, timing: TimingActions
) {
    var activeModule by rememberSaveable { mutableStateOf<StudioModule?>(null) }
    val workspace = state.workspace
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column(Modifier.padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("功能模块", style = MaterialTheme.typography.headlineSmall)
                HintText("功能模块负责生成经过约束验证的设备树事务；能力扫描只负责发现，不会自动把检测结果变成写入。")
            }
        }
        if (workspace != null) {
            item { CapabilityScanCard(state) }
        }
        if (workspace == null) {
            item {
                EmptyState(
                    Icons.Default.FolderOpen,
                    "还没有工作区",
                    "先到“概览”导入 dtbo.img，或在 Root 设备上提取当前 DTBO 分区。"
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val refreshFinding = state.capabilityReport?.finding(CapabilityKind.REFRESH_RATE)
                    ModuleCard("刷新率", capabilitySubtitle(state, refreshFinding, workspace.candidates.size), Icons.Default.Monitor, (refreshFinding?.matchCount ?: workspace.candidates.size) > 0, activeModule == StudioModule.REFRESH_RATE, Modifier.weight(1f)) { activeModule = if (activeModule == StudioModule.REFRESH_RATE) null else StudioModule.REFRESH_RATE }
                    ModuleCard("Charging", capabilitySubtitle(state, state.capabilityReport?.finding(CapabilityKind.CHARGING), 0), Icons.Default.BatteryChargingFull, !state.busy, activeModule == StudioModule.CHARGING, Modifier.weight(1f)) { activeModule = if (activeModule == StudioModule.CHARGING) null else StudioModule.CHARGING }
                    ModuleCard("高级属性", "设备树编辑器 · 始终可用", Icons.Default.Code, false, modifier = Modifier.weight(1f))
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
    val report = state.capabilityReport
    val (statusText, statusTone) = when {
        state.capabilityScanInProgress -> "扫描中…" to Tone.Warning
        report != null -> "已完成" to Tone.Success
        else -> "等待扫描" to Tone.Neutral
    }
    SectionCard(
        title = "设备树能力扫描",
        subtitle = report?.let { "${it.scannedEntryCount} 个 DTB · ${it.nodeCount} 个节点 · ${it.propertyCount} 个属性" },
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
                        "${finding.kind.displayName}: ${finding.status.displayName} ${finding.matchCount}",
                        tone = when (finding.status) {
                            CapabilityStatus.AVAILABLE -> Tone.Success
                            CapabilityStatus.ANALYSIS_ONLY -> Tone.Primary
                            CapabilityStatus.NOT_FOUND -> Tone.Neutral
                        }
                    )
                }
            }
        } else {
            HintText("导入 DTBO 后自动识别刷新率时序和 Charging 参数。")
        }
    }
}

private fun capabilitySubtitle(
    state: MainUiState,
    finding: CapabilityFinding?,
    fallbackCount: Int
): String
{
    if (state.capabilityScanInProgress && finding == null)
    {
        return "扫描中…"
    }
    if (finding == null)
    {
        return if (fallbackCount > 0) "$fallbackCount 个候选" else "等待能力扫描"
    }
    return when (finding.status)
    {
        CapabilityStatus.AVAILABLE -> "可用 · ${finding.matchCount} 个"
        CapabilityStatus.ANALYSIS_ONLY -> "可分析 · ${finding.matchCount} 个"
        CapabilityStatus.NOT_FOUND -> "当前 DTBO 未发现"
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
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            SectionCard(
                title = "环境状态",
                icon = Icons.Default.PhoneAndroid,
                trailing = {
                    if (state.rootState.granted) StatusPill("Root 已授权", tone = Tone.Success)
                    else StatusPill("未授权", tone = Tone.Warning)
                }
            ) {
                if (!state.rootState.granted) HintText(state.rootState.detail)
                KeyValueRow("DTBO 分区", state.slotInfo?.blockDevice ?: "分区路径检测中", monospace = true)
                ActionRow {
                    if (!state.rootState.granted) OutlinedButton(navigation.onRequestRoot, enabled = state.rootState.suPresent) { IconLabel(Icons.Default.Lock, "请求 Root") }
                    OutlinedButton(navigation.onRefreshEnvironment) { IconLabel(Icons.Default.Refresh, "重新探测") }
                }
            }
        }
        item { NavigationEntry(Icons.Default.Restore, "备份与恢复", "已保存 " + state.backups.size + " 个 DTBO 备份", navigation.onOpenRollback) }
        item { NavigationEntry(Icons.Default.Settings, "高级设置", "缓存、日志与维护选项", navigation.onOpenAdvancedSettings) }
        item { NavigationEntry(Icons.Default.Info, "关于 DTBO Studio", "版本、项目说明与免责声明", navigation.onOpenAbout) }
    }
}
