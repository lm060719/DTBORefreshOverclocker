package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
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
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.ResolutionScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class StudioTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    MODULES("功能模块", Icons.Default.Apps),
    DEVICE_TREE("设备树", Icons.Default.AccountTree),
    SETTINGS("设置", Icons.Default.Settings)
}

private enum class StudioModule { REFRESH_RATE, RESOLUTION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    state: MainUiState, pagerState: PagerState, pageStateHolder: SaveableStateHolder,
    onImport: () -> Unit, onExtract: () -> Unit, onRefreshEnvironment: () -> Unit,
    onOpenRollback: () -> Unit, onOpenAdvancedSettings: () -> Unit, onOpenAbout: () -> Unit,
    onRequestRoot: () -> Unit, onSelect: (String) -> Unit, onTarget: (Int) -> Unit,
    onStrategy: (PatchStrategy) -> Unit, onPatchMode: (PatchMode) -> Unit,
    onCustomPixelClock: (String) -> Unit, onCustomVfp: (String) -> Unit,
    onCustomVbp: (String) -> Unit, onCustomHfp: (String) -> Unit, onCustomHbp: (String) -> Unit,
    onApplySuggestedCustom: () -> Unit, onStageChange: () -> Unit,
    onResolutionWidth: (String) -> Unit, onResolutionHeight: (String) -> Unit,
    onResolutionScope: (ResolutionScope) -> Unit, onResolutionPreset: (Int, Int) -> Unit,
    onStageResolution: () -> Unit,
    onSetDeviceTreeProperty: (Int, String, String, String?) -> Unit,
    onAddDeviceTreeProperty: (Int, String, String, String?) -> Unit,
    onDeleteDeviceTreeProperty: (Int, String, String) -> Unit,
    onAddDeviceTreeNode: (Int, String, String) -> Unit,
    onCloneDeviceTreeNode: (Int, String, String) -> Unit,
    onRenameDeviceTreeNode: (Int, String, String) -> Unit,
    onDeleteDeviceTreeNode: (Int, String) -> Unit,
    onUndoDeviceTreeChange: (String) -> Unit,
    onPackage: () -> Unit, onReset: () -> Unit, onSavePatched: (File) -> Unit,
    onRecoveryZip: () -> Unit, onFastbootBundle: () -> Unit, onFlash: () -> Unit,
    onExportBackup: (File) -> Unit, onExportRescue: (File) -> Unit, onScreenshot: () -> Unit,
    onCopy: (String) -> Unit, onClearLogs: () -> Unit
) {
    StudioNavigation(pagerState, pageStateHolder, !state.busy, onOpenRollback, onRefreshEnvironment) { tab, padding ->
        when (tab) {
            StudioTab.OVERVIEW -> OverviewTab(state, padding, onImport, onExtract, onPackage, onReset, onSavePatched, onRecoveryZip, onFastbootBundle, onFlash, onExportBackup, onExportRescue, onScreenshot, onCopy, onClearLogs)
            StudioTab.MODULES -> ModulesTab(
                state, padding, onSelect, onTarget, onStrategy, onPatchMode,
                onCustomPixelClock, onCustomVfp, onCustomVbp, onCustomHfp, onCustomHbp,
                onApplySuggestedCustom, onStageChange, onResolutionWidth, onResolutionHeight,
                onResolutionScope, onResolutionPreset, onStageResolution
            )
            StudioTab.DEVICE_TREE -> DeviceTreeScreen(
                state = state,
                contentPadding = padding,
                onSetProperty = onSetDeviceTreeProperty,
                onAddProperty = onAddDeviceTreeProperty,
                onDeleteProperty = onDeleteDeviceTreeProperty,
                onAddNode = onAddDeviceTreeNode,
                onCloneNode = onCloneDeviceTreeNode,
                onRenameNode = onRenameDeviceTreeNode,
                onDeleteNode = onDeleteDeviceTreeNode,
                onUndoChange = onUndoDeviceTreeChange
            )
            StudioTab.SETTINGS -> SettingsHubTab(state, padding, onRequestRoot, onRefreshEnvironment, onOpenRollback, onOpenAdvancedSettings, onOpenAbout)
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
    state: MainUiState, padding: PaddingValues, onImport: () -> Unit, onExtract: () -> Unit,
    onPackage: () -> Unit, onReset: () -> Unit, onSavePatched: (File) -> Unit, onRecoveryZip: () -> Unit,
    onFastbootBundle: () -> Unit, onFlash: () -> Unit, onExportBackup: (File) -> Unit,
    onExportRescue: (File) -> Unit, onScreenshot: () -> Unit, onCopy: (String) -> Unit, onClearLogs: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "top") { Spacer(Modifier.height(2.dp)) }
        item(key = "hero") { StudioHeroCard(state) }
        item(key = "source") { SourceCard(state, onImport, onExtract) }
        if (state.workspace != null) {
            item(key = "summary") { ImageSummaryCard(state) }
            if (state.stagedChanges.isNotEmpty()) item(key = "staged") { StagedChangesCard(state.stagedChanges, onPackage, onReset, state.busy) }
            if (state.moduleStagedChanges.isNotEmpty()) item(key = "module-staged") {
                ModuleStagedChangesCard(state, onPackage, onReset)
            }
            if (state.deviceTreeChanges.isNotEmpty()) item(key = "device-tree-staged") {
                DeviceTreeStagedChangesCard(state, onPackage, onReset)
            }
        }
        state.patchReport?.let { report -> item(key = "output") { OutputCard(state, { onSavePatched(report.outputImage) }, onRecoveryZip, onFastbootBundle, onFlash) } }
        state.lastFlash?.let { flash -> item(key = "rescue") { RescueMemoCard(state, onCopy, { onExportBackup(flash.backupFile) }, { onExportRescue(flash.rescueZip) }, onScreenshot) } }
        item(key = "terminal") { TerminalCard(state.logs, onClearLogs) }
        item(key = "status") { Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}


@Composable
private fun ModuleStagedChangesCard(
    state: MainUiState,
    onPackage: () -> Unit,
    onReset: () -> Unit
)
{
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "功能模块修改 · ${state.moduleStagedChanges.size} 项",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.moduleStagedChanges.takeLast(4).forEach { change ->
                Text(
                    "• ${change.module.displayName}: ${change.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.moduleStagedChanges.any { !it.directFlashAllowed }) {
                Text(
                    "当前包含仅允许导出验证的功能模块修改，因此 Root 直刷已禁用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPackage, enabled = !state.busy) { Text("集中打包") }
                OutlinedButton(onClick = onReset, enabled = !state.busy) { Text("全部重置") }
            }
        }
    }
}

@Composable
private fun DeviceTreeStagedChangesCard(
    state: MainUiState,
    onPackage: () -> Unit,
    onReset: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "通用设备树修改 · ${state.deviceTreeChanges.size} 项",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.deviceTreeChanges.takeLast(4).forEach { change ->
                Text(
                    "• ${change.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "自由设备树编辑当前阶段只允许导出验证，禁止 Root 直刷。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPackage, enabled = !state.busy) { Text("集中打包") }
                OutlinedButton(onClick = onReset, enabled = !state.busy) { Text("全部重置") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioHeroCard(state: MainUiState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android Device Tree Toolkit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("导入、分析、编辑、验证并重新构建 DTBO。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    state: MainUiState, padding: PaddingValues, onSelect: (String) -> Unit, onTarget: (Int) -> Unit,
    onStrategy: (PatchStrategy) -> Unit, onPatchMode: (PatchMode) -> Unit,
    onCustomPixelClock: (String) -> Unit, onCustomVfp: (String) -> Unit, onCustomVbp: (String) -> Unit,
    onCustomHfp: (String) -> Unit, onCustomHbp: (String) -> Unit, onApplySuggestedCustom: () -> Unit,
    onStageChange: () -> Unit, onResolutionWidth: (String) -> Unit, onResolutionHeight: (String) -> Unit,
    onResolutionScope: (ResolutionScope) -> Unit, onResolutionPreset: (Int, Int) -> Unit,
    onStageResolution: () -> Unit
) {
    var activeModule by rememberSaveable { mutableStateOf<StudioModule?>(null) }
    val workspace = state.workspace
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Column { Text("功能模块", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("专用模块负责常见硬件配置；通用修改最终统一落到设备树编辑器。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (workspace == null) {
            item { WorkspaceRequiredCard() }
        } else {
            item { Text("显示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModuleCard("刷新率", workspace.candidates.size.toString() + " 个时序候选", Icons.Default.Monitor, workspace.candidates.isNotEmpty(), activeModule == StudioModule.REFRESH_RATE) { activeModule = if (activeModule == StudioModule.REFRESH_RATE) null else StudioModule.REFRESH_RATE }
                val resolutionCandidates = workspace.candidates.count { it.hActive != null && it.vActive != null }
                ModuleCard("分辨率", "$resolutionCandidates 个可分析档位", Icons.Default.AspectRatio, resolutionCandidates > 0, activeModule == StudioModule.RESOLUTION) { activeModule = if (activeModule == StudioModule.RESOLUTION) null else StudioModule.RESOLUTION }
                ModuleCard("DSC", "规划中", Icons.Default.Tune, false)
                ModuleCard("亮度 / HBM", "规划中", Icons.Default.Brightness6, false)
            } }
            item { Text("硬件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModuleCard("Thermal", "规划中", Icons.Default.Thermostat, false)
                ModuleCard("Charging", "规划中", Icons.Default.BatteryChargingFull, false)
                ModuleCard("Touch", "规划中", Icons.Default.TouchApp, false)
                ModuleCard("高级属性", "设备树编辑器", Icons.Default.Code, false)
            } }
            if (activeModule == StudioModule.REFRESH_RATE && workspace.candidates.isNotEmpty()) {
                item { HorizontalDivider() }
                item { TimingPanel(state, onSelect, onTarget, onStrategy, onPatchMode, onCustomPixelClock, onCustomVfp, onCustomVbp, onCustomHfp, onCustomHbp, onApplySuggestedCustom, onStageChange) }
            }
            if (activeModule == StudioModule.RESOLUTION) {
                item { HorizontalDivider() }
                item {
                    ResolutionPanel(
                        state = state,
                        onSelect = onSelect,
                        onWidth = onResolutionWidth,
                        onHeight = onResolutionHeight,
                        onScope = onResolutionScope,
                        onPreset = onResolutionPreset,
                        onStage = onStageResolution
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ModuleCard(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, active: Boolean = false, onClick: () -> Unit = {}) {
    Card(Modifier.width(164.dp).clickable(enabled = enabled, onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.28f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null); Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WorkspaceRequiredCard() {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.FolderOpen, null); Text("还没有工作区", fontWeight = FontWeight.SemiBold); Text("先到“概览”导入 dtbo.img，或在 Root 设备上提取当前 DTBO 分区。") } }
}

@Composable
private fun SettingsHubTab(state: MainUiState, padding: PaddingValues, onRequestRoot: () -> Unit, onRefreshEnvironment: () -> Unit, onOpenRollback: () -> Unit, onOpenAdvancedSettings: () -> Unit, onOpenAbout: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("环境状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (state.rootState.granted) "Root 已授权" else state.rootState.detail)
            Text(state.slotInfo?.blockDevice ?: "分区路径检测中", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.rootState.granted) OutlinedButton(onRequestRoot, enabled = state.rootState.suPresent) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(6.dp)); Text("请求 Root") }
                OutlinedButton(onRefreshEnvironment) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("重新探测") }
            }
        } } }
        item { SettingsEntry(Icons.Default.Restore, "备份与恢复", "已保存 " + state.backups.size + " 个 DTBO 备份", onOpenRollback) }
        item { SettingsEntry(Icons.Default.Settings, "高级设置", "缓存、日志与维护选项", onOpenAdvancedSettings) }
        item { SettingsEntry(Icons.Default.Info, "关于 DTBO Studio", "版本、项目说明与免责声明", onOpenAbout) }
    }
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null) } }
        Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
}
