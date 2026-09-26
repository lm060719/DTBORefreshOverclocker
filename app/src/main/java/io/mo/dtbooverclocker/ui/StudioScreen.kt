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
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item(key = "top") { Spacer(Modifier.height(2.dp)) }
        item(key = "hero") { StudioHeroCard(state) }
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
        item(key = "status") { Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xl)) }
    }
}


@Composable
private fun TransactionQueueCard(
    state: MainUiState,
    onPackage: () -> Unit,
    onReset: () -> Unit,
    onUndoLastTransaction: () -> Unit
)
{
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "设备树事务 · ${state.transactions.size} 个 / ${state.transactions.sumOf { it.operationCount }} 个底层操作",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.transactions.takeLast(5).forEach { transaction ->
                Text(
                    "• ${transaction.kind.displayName} · ${transaction.risk.displayName} · ${transaction.operationCount} ops\n  ${transaction.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = onPackage, enabled = !state.busy) { Text("集中打包") }
                OutlinedButton(onClick = onUndoLastTransaction, enabled = !state.busy) { Text("撤销最近事务") }
                OutlinedButton(onClick = onReset, enabled = !state.busy) { Text("全部重置") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioHeroCard(state: MainUiState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Android Device Tree Toolkit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("导入、分析、编辑、验证并重新构建 DTBO。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AssistChip({}, { Text(if (state.rootState.granted) "Root ✓" else "免 Root 可用") })
                state.slotInfo?.let { AssistChip({}, { Text(it.label) }) }
                AssistChip({}, { Text(if (state.workspace != null) "工作区已加载" else "等待镜像") })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModulesTab(
    state: MainUiState, padding: PaddingValues, timing: TimingActions
) {
    var activeModule by rememberSaveable { mutableStateOf<StudioModule?>(null) }
    val workspace = state.workspace
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Column { Text("功能模块", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("功能模块负责生成经过约束验证的设备树事务；能力扫描只负责发现，不会自动把检测结果变成写入。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (workspace != null) {
            item { CapabilityScanCard(state) }
        }
        if (workspace == null) {
            item { WorkspaceRequiredCard() }
        } else {
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                val refreshFinding = state.capabilityReport?.finding(CapabilityKind.REFRESH_RATE)
                ModuleCard("刷新率", capabilitySubtitle(state, refreshFinding, workspace.candidates.size), Icons.Default.Monitor, (refreshFinding?.matchCount ?: workspace.candidates.size) > 0, activeModule == StudioModule.REFRESH_RATE) { activeModule = if (activeModule == StudioModule.REFRESH_RATE) null else StudioModule.REFRESH_RATE }
                ModuleCard("Charging", capabilitySubtitle(state, state.capabilityReport?.finding(CapabilityKind.CHARGING), 0), Icons.Default.BatteryChargingFull, !state.busy, activeModule == StudioModule.CHARGING) { activeModule = if (activeModule == StudioModule.CHARGING) null else StudioModule.CHARGING }
                ModuleCard("高级属性", "设备树编辑器 · 始终可用", Icons.Default.Code, false)
            } }
            if (activeModule == StudioModule.REFRESH_RATE && workspace.candidates.isNotEmpty()) {
                item { HorizontalDivider() }
                item { TimingPanel(state, timing.onSelect, timing.onTarget, timing.onStrategy, timing.onPatchMode, timing.onCustomPixelClock, timing.onCustomVfp, timing.onCustomVbp, timing.onCustomHfp, timing.onCustomHbp, timing.onApplySuggestedCustom, timing.onStageChange) }
            }
            if (activeModule == StudioModule.CHARGING) {
                item { HorizontalDivider() }
                item(key = "charging_panel") { ChargingPanel(state, timing.onStageCharging) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityScanCard(state: MainUiState)
{
    val report = state.capabilityReport
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设备树能力扫描", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.capabilityScanInProgress) "扫描中…" else if (report != null) "已完成" else "等待扫描",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (report != null) {
                Text(
                    "${report.scannedEntryCount} 个 DTB · ${report.nodeCount} 个节点 · ${report.propertyCount} 个属性",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    report.findings.forEach { finding ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${finding.kind.displayName}: ${finding.status.displayName} ${finding.matchCount}") }
                        )
                    }
                }
            } else {
                Text(
                    "导入 DTBO 后自动识别刷新率时序和 Charging 参数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
private fun ModuleCard(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, active: Boolean = false, onClick: () -> Unit = {}) {
    Card(Modifier.width(164.dp).clickable(enabled = enabled, onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.28f))) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { Icon(icon, null); Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WorkspaceRequiredCard() {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { Icon(Icons.Default.FolderOpen, null); Text("还没有工作区", fontWeight = FontWeight.SemiBold); Text("先到“概览”导入 dtbo.img，或在 Root 设备上提取当前 DTBO 分区。") } }
}

@Composable
private fun SettingsHubTab(state: MainUiState, padding: PaddingValues, navigation: NavigationActions) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("环境状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (state.rootState.granted) "Root 已授权" else state.rootState.detail)
            Text(state.slotInfo?.blockDevice ?: "分区路径检测中", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (!state.rootState.granted) OutlinedButton(navigation.onRequestRoot, enabled = state.rootState.suPresent) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(6.dp)); Text("请求 Root") }
                OutlinedButton(navigation.onRefreshEnvironment) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重新探测") }
            }
        } } }
        item { SettingsEntry(Icons.Default.Restore, "备份与恢复", "已保存 " + state.backups.size + " 个 DTBO 备份", navigation.onOpenRollback) }
        item { SettingsEntry(Icons.Default.Settings, "高级设置", "缓存、日志与维护选项", navigation.onOpenAdvancedSettings) }
        item { SettingsEntry(Icons.Default.Info, "关于 DTBO Studio", "版本、项目说明与免责声明", navigation.onOpenAbout) }
    }
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null) } }
        Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
}
