package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import java.io.File

enum class StudioTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    MODULES("功能模块", Icons.Default.Apps),
    DEVICE_TREE("设备树", Icons.Default.AccountTree),
    SETTINGS("设置", Icons.Default.Settings)
}

private enum class StudioModule { REFRESH_RATE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    state: MainUiState, selectedTab: StudioTab, onTabSelected: (StudioTab) -> Unit,
    onImport: () -> Unit, onExtract: () -> Unit, onRefreshEnvironment: () -> Unit,
    onOpenRollback: () -> Unit, onOpenAdvancedSettings: () -> Unit, onOpenAbout: () -> Unit,
    onRequestRoot: () -> Unit, onSelect: (String) -> Unit, onTarget: (Int) -> Unit,
    onStrategy: (PatchStrategy) -> Unit, onPatchMode: (PatchMode) -> Unit,
    onCustomPixelClock: (String) -> Unit, onCustomVfp: (String) -> Unit,
    onCustomVbp: (String) -> Unit, onCustomHfp: (String) -> Unit, onCustomHbp: (String) -> Unit,
    onApplySuggestedCustom: () -> Unit, onStageChange: () -> Unit,
    onPackage: () -> Unit, onReset: () -> Unit, onSavePatched: (File) -> Unit,
    onRecoveryZip: () -> Unit, onFastbootBundle: () -> Unit, onFlash: () -> Unit,
    onExportBackup: (File) -> Unit, onExportRescue: (File) -> Unit, onScreenshot: () -> Unit,
    onCopy: (String) -> Unit, onClearLogs: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("DTBO Studio", fontWeight = FontWeight.SemiBold); Text(selectedTab.label, style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    if (selectedTab != StudioTab.SETTINGS) {
                        IconButton(onClick = onOpenRollback) { Icon(Icons.Default.Restore, "备份与恢复") }
                        IconButton(onClick = onRefreshEnvironment) { Icon(Icons.Default.Refresh, "刷新环境") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar { StudioTab.entries.forEach { tab ->
                NavigationBarItem(selectedTab == tab, { onTabSelected(tab) }, { Icon(tab.icon, tab.label) }, label = { Text(tab.label) })
            } }
        }
    ) { padding ->
        when (selectedTab) {
            StudioTab.OVERVIEW -> OverviewTab(state, padding, onImport, onExtract, onPackage, onReset, onSavePatched, onRecoveryZip, onFastbootBundle, onFlash, onExportBackup, onExportRescue, onScreenshot, onCopy, onClearLogs)
            StudioTab.MODULES -> ModulesTab(state, padding, onSelect, onTarget, onStrategy, onPatchMode, onCustomPixelClock, onCustomVfp, onCustomVbp, onCustomHfp, onCustomHbp, onApplySuggestedCustom, onStageChange)
            StudioTab.DEVICE_TREE -> DeviceTreeScreen(state, padding)
            StudioTab.SETTINGS -> SettingsHubTab(state, padding, onRequestRoot, onRefreshEnvironment, onOpenRollback, onOpenAdvancedSettings, onOpenAbout)
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
        item { Spacer(Modifier.height(2.dp)) }
        item { StudioHeroCard(state) }
        item { SourceCard(state, onImport, onExtract) }
        if (state.workspace != null) {
            item { ImageSummaryCard(state) }
            if (state.stagedChanges.isNotEmpty()) item { StagedChangesCard(state.stagedChanges, onPackage, onReset, state.busy) }
        }
        state.patchReport?.let { report -> item { OutputCard(state, { onSavePatched(report.outputImage) }, onRecoveryZip, onFastbootBundle, onFlash) } }
        state.lastFlash?.let { flash -> item { RescueMemoCard(state, onCopy, { onExportBackup(flash.backupFile) }, { onExportRescue(flash.rescueZip) }, onScreenshot) } }
        item { TerminalCard(state.logs, onClearLogs) }
        item { Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioHeroCard(state: MainUiState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android Device Tree Toolkit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("导入、分析、编辑、验证并重新构建 DTBO。刷新率现在只是功能模块之一。", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onCustomHfp: (String) -> Unit, onCustomHbp: (String) -> Unit, onApplySuggestedCustom: () -> Unit, onStageChange: () -> Unit
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
                ModuleCard("分辨率", "规划中", Icons.Default.AspectRatio, false)
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