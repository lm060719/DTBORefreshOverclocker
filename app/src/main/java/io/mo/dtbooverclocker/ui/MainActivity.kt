package io.mo.dtbooverclocker.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.SourceMode
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.OverclockPreviewCard
import io.mo.dtbooverclocker.ui.components.TimingCandidateSelector
import io.mo.dtbooverclocker.ui.components.TimingGeometryChart
import io.mo.dtbooverclocker.ui.components.TimingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppScreen {
    MAIN,
    ROLLBACK,
    SETTINGS,
    ABOUT
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                DtboOverclockerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DtboOverclockerApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity

    var pendingBinary by remember { mutableStateOf<File?>(null) }
    var pendingZip by remember { mutableStateOf<File?>(null) }
    var showFlashDialog by remember { mutableStateOf(false) }

    val openImage = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importImage)
    }

    val saveBinary = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingBinary
        if (uri != null && file != null) viewModel.exportFile(file, uri)
        pendingBinary = null
    }

    val saveZip = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val file = pendingZip
        if (uri != null && file != null) viewModel.exportFile(file, uri)
        pendingZip = null
    }

    val saveScreenshot = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) captureWindowToPng(activity, uri)
    }

    val saveLogs = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            viewModel.exportLogsToUri(uri) { success ->
                val msg = if (success) "完整日志已成功导出" else "日志导出失败"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }

    when (currentScreen) {
        AppScreen.ROLLBACK -> {
            RollbackScreen(
                state = state,
                onNavigateBack = { currentScreen = AppScreen.MAIN },
                onRefresh = viewModel::loadBackups,
                onManualBackup = { desc ->
                    viewModel.createManualBackup(desc) { ok, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                onVerifyMd5 = viewModel::verifyBackupMd5,
                onExportBackup = { record ->
                    viewModel.exportBackup(record) { ok, path ->
                        val tip = if (ok) "已成功导出至 $path" else "导出失败：$path"
                        Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
                    }
                },
                onFlashBackup = { record ->
                    viewModel.flashBackup(record) { ok, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onDeleteBackup = { record ->
                    viewModel.deleteBackup(record) { ok ->
                        val tip = if (ok) "已删除备份：${record.fileName}" else "删除失败"
                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        AppScreen.SETTINGS -> {
            SettingsScreen(
                state = state,
                onNavigateBack = { currentScreen = AppScreen.MAIN },
                onNavigateToAbout = { currentScreen = AppScreen.ABOUT },
                onNavigateToRollback = { currentScreen = AppScreen.ROLLBACK },
                onRequestRoot = viewModel::requestRoot,
                onRefreshEnvironment = viewModel::refreshEnvironment,
                onRefreshCacheSize = viewModel::refreshCacheSize,
                onClearAllCache = viewModel::clearAllCache,
                onRefreshLogStats = viewModel::refreshLogStats,
                onExportLogs = {
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    saveLogs.launch("DTBO_Log_${timestamp}.txt")
                },
                onClearAllLogs = viewModel::clearLogFiles
            )
        }
        AppScreen.ABOUT -> {
            AboutScreen(
                onNavigateBack = { currentScreen = AppScreen.SETTINGS }
            )
        }
        AppScreen.MAIN -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("DTBO Refresh Overclocker", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Dual-Mode · 单槽位安全策略",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { currentScreen = AppScreen.ROLLBACK }) {
                                BadgedBox(
                                    badge = {
                                        if (state.backups.isNotEmpty()) {
                                            Badge { Text("${state.backups.size}") }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = "镜像回滚")
                                }
                            }
                            IconButton(onClick = viewModel::refreshEnvironment) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新环境")
                            }
                            IconButton(onClick = { currentScreen = AppScreen.SETTINGS }) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(Modifier.height(2.dp)) }

                    item {
                        SourceCard(
                            state = state,
                            onMode = viewModel::setSourceMode,
                            onImport = { openImage.launch(arrayOf("application/octet-stream", "*/*")) },
                            onExtract = viewModel::extractActivePartition
                        )
                    }

            state.workspace?.let { workspace ->
                item {
                    ImageSummaryCard(state)
                }

                if (workspace.candidates.isNotEmpty()) {
                    item {
                        TimingPanel(
                            state = state,
                            onSelect = viewModel::selectCandidate,
                            onTarget = viewModel::setTargetHz,
                            onStrategy = viewModel::setStrategy,
                            onPatchMode = viewModel::setPatchMode,
                            onPatch = viewModel::patchSelected
                        )
                    }
                }
            }

            state.patchReport?.let { report ->
                item {
                    OutputCard(
                        state = state,
                        onSavePatched = {
                            pendingBinary = report.outputImage
                            saveBinary.launch(report.outputImage.name)
                        },
                        onRecoveryZip = {
                            viewModel.prepareRecoveryZip { file ->
                                pendingZip = file
                                saveZip.launch(file.name)
                            }
                        },
                        onFastbootBundle = {
                            viewModel.prepareFastbootBundle { file ->
                                pendingZip = file
                                saveZip.launch(file.name)
                            }
                        },
                        onFlash = { showFlashDialog = true }
                    )
                }
            }

            state.lastFlash?.let { flash ->
                item {
                    RescueMemoCard(
                        state = state,
                        onCopy = { text -> copyText(context, "DTBO rollback", text) },
                        onExportBackup = {
                            pendingBinary = flash.backupFile
                            saveBinary.launch(flash.backupFile.name)
                        },
                        onExportRescue = {
                            pendingZip = flash.rescueZip
                            saveZip.launch(flash.rescueZip.name)
                        },
                        onScreenshot = {
                            saveScreenshot.launch("DTBO_rescue_memo_${System.currentTimeMillis()}.png")
                        }
                    )
                }
            }

            item {
                TerminalCard(
                    logs = state.logs,
                    onClear = viewModel::clearLogs
                )
            }

            item {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}
}

    if (state.busy) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center
        ) {
            Card {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(state.status)
                }
            }
        }
    }

    if (showFlashDialog) {
        DangerousFlashDialog(
            targetHz = state.targetHz,
            partition = state.slotInfo?.blockDevice.orEmpty(),
            onDismiss = { showFlashDialog = false },
            onConfirm = {
                showFlashDialog = false
                viewModel.flashPatched()
            }
        )
    }
}

@Composable
private fun SourceCard(
    state: MainUiState,
    onMode: (SourceMode) -> Unit,
    onImport: () -> Unit,
    onExtract: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("镜像来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = state.sourceMode == SourceMode.LOCAL_IMAGE,
                    onClick = { onMode(SourceMode.LOCAL_IMAGE) },
                    label = { Text("手动导入 · 免 Root") }
                )
                FilterChip(
                    selected = state.sourceMode == SourceMode.ROOT_PARTITION,
                    onClick = { onMode(SourceMode.ROOT_PARTITION) },
                    label = { Text("当前分区 · Root") }
                )
            }

            if (state.sourceMode == SourceMode.LOCAL_IMAGE) {
                Button(onClick = onImport) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择 dtbo.img")
                }
            } else {
                Button(onClick = onExtract, enabled = state.rootState.suPresent) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("提取当前活跃槽位")
                }
                Text(
                    "只读取 ${state.slotInfo?.blockDevice ?: "当前 dtbo"}，不会读取或覆盖另一槽位。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ImageSummaryCard(state: MainUiState) {
    val workspace = state.workspace ?: return
    val groups = remember(workspace.candidates) {
        TimingUtils.groupCandidates(workspace.candidates)
    }
    val devCount = remember(groups) { groups.keys.count { it.isDeviceSpecific } }
    val panelCount = groups.size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("镜像解析结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "DTBO v${workspace.metadata.version}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("DTB: ${workspace.metadata.entries.size}") }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(if (devCount > 0) "屏幕面板: $panelCount (机型专属: $devCount)" else "屏幕面板: $panelCount")
                    }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("时序候选: ${workspace.candidates.size}") }
                )
                if (state.activePanelDisplayName != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("在用: ${state.activePanelDisplayName}") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }

            if (devCount > 0) {
                Text(
                    "检测到 $devCount 个机型专属面板（如 O1-38 / O1-42），其余 ${panelCount - devCount} 个为高通公版/仿真测试屏节点，已优先为您展示机型屏幕。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                workspace.inputImage.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimingPanel(
    state: MainUiState,
    onSelect: (String) -> Unit,
    onTarget: (Int) -> Unit,
    onStrategy: (PatchStrategy) -> Unit,
    onPatchMode: (PatchMode) -> Unit,
    onPatch: () -> Unit
) {
    val workspace = state.workspace ?: return
    val selected = workspace.candidates.firstOrNull { it.id == state.selectedCandidateId }
        ?: workspace.candidates.first()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "参数微调与超频推演",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "${workspace.candidates.size} 个候选",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 1. 屏幕面板与候选档位选择器（智能分组、卡片式呈现、折叠底层路径）
            TimingCandidateSelector(
                candidates = workspace.candidates,
                selectedCandidateId = selected.id,
                onSelect = onSelect,
                activePanelIdentifier = state.activePanelIdentifier,
                activePanelDisplayName = state.activePanelDisplayName,
                activePanelSource = state.activePanelSource,
                onAddNewTiming = {
                    onPatchMode(PatchMode.APPEND_NEW)
                }
            )

            HorizontalDivider()

            // 2. DSI 时序几何剖面图（水平与垂直显像、前肩、同步、后肩比例分布）
            TimingGeometryChart(candidate = selected)

            HorizontalDivider()

            // 3. 目标刷新率调节与快捷预设芯片
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "目标刷新率：${state.targetHz} Hz",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                // 快捷预设增量芯片
                val presets = remember(selected.currentHz) {
                    val base = selected.currentHz
                    val list = mutableListOf<Int>()
                    if (base in 60..89) list += listOf(75, 90, 120)
                    else if (base in 90..119) list += listOf(110, 120, 144)
                    else if (base in 120..143) list += listOf(135, 144, 165)
                    else if (base >= 144) list += listOf(base + 15, base + 24, 165, 180)
                    else list += listOf(60, 90, 120)
                    list.filter { it > base && it <= 360 }.distinct().take(4)
                }

                if (presets.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "快捷预设:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        presets.forEach { presetHz ->
                            AssistChip(
                                onClick = { onTarget(presetHz) },
                                label = { Text("$presetHz Hz") }
                            )
                        }
                    }
                }

                val sliderMax = maxOf(165, selected.currentHz + 90).coerceAtMost(360)
                Slider(
                    value = state.targetHz.toFloat().coerceIn(30f, sliderMax.toFloat()),
                    onValueChange = { onTarget(it.toInt()) },
                    valueRange = 30f..sliderMax.toFloat()
                )
                OutlinedTextField(
                    value = state.targetHz.toString(),
                    onValueChange = { value -> value.filter(Char::isDigit).toIntOrNull()?.let(onTarget) },
                    label = { Text("目标刷新率数值 (Hz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            // 4. 计算策略选择
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("计算策略", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PatchStrategy.entries.forEach { strategy ->
                        FilterChip(
                            selected = state.strategy == strategy,
                            onClick = { onStrategy(strategy) },
                            label = { Text(strategy.displayName) }
                        )
                    }
                }
                Text(state.strategy.description, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // 5. 操作模式选择（覆盖已有档位 vs 新增独立档位）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("操作模式", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.patchMode == mode,
                            onClick = { onPatchMode(mode) },
                            label = { Text(mode.displayName) },
                            leadingIcon = {
                                Icon(
                                    if (mode == PatchMode.APPEND_NEW) Icons.Default.Add else Icons.Default.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
                Text(state.patchMode.description, style = MaterialTheme.typography.bodySmall)
            }

            // 6. 实时超频推演卡片（包含原值 vs 目标值、时钟倍率、消隐行数和安全评级）
            OverclockPreviewCard(
                candidate = selected,
                targetHz = state.targetHz,
                strategy = state.strategy,
                mode = state.patchMode
            )

            // 7. 执行修补 / 新增按钮
            Button(onClick = onPatch, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (state.patchMode == PatchMode.APPEND_NEW) Icons.Default.Add else Icons.Default.Build,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.patchMode == PatchMode.APPEND_NEW) "追加为新档位并重打包" else "修补已有档位并重打包")
            }
        }
    }
}

@Composable
private fun OutputCard(
    state: MainUiState,
    onSavePatched: () -> Unit,
    onRecoveryZip: () -> Unit,
    onFastbootBundle: () -> Unit,
    onFlash: () -> Unit
) {
    val report = state.patchReport ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("输出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val modeTitle = if (report.mode == PatchMode.APPEND_NEW) {
                "新增独立档位：${report.targetHz} Hz (基于原 ${report.originalHz} Hz 模板) · ${report.strategy.displayName}"
            } else {
                "${report.originalHz} Hz → ${report.targetHz} Hz · ${report.strategy.displayName}"
            }
            Text(modeTitle, fontWeight = FontWeight.Medium)
            report.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            report.warnings.forEach {
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = onSavePatched, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存 dtbo_patched.img")
            }
            OutlinedButton(onClick = onRecoveryZip, modifier = Modifier.fillMaxWidth()) {
                Text("导出 Recovery 刷机 Zip")
            }
            OutlinedButton(onClick = onFastbootBundle, modifier = Modifier.fillMaxWidth()) {
                Text("导出 PC Fastboot 一键包")
            }

            val canFlash = state.rootState.granted &&
                state.sourceMode == SourceMode.ROOT_PARTITION &&
                report.strategy != PatchStrategy.FRAMERATE_ONLY
            Button(
                onClick = onFlash,
                enabled = canFlash,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FlashOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("直接刷写当前槽位")
            }
            if (!canFlash) {
                Text(
                    "直接刷写要求：Root 已授权、镜像来自当前手机分区、且不是“仅 Framerate”策略。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun RescueMemoCard(
    state: MainUiState,
    onCopy: (String) -> Unit,
    onExportBackup: () -> Unit,
    onExportRescue: () -> Unit,
    onScreenshot: () -> Unit
) {
    val flash = state.lastFlash ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("救砖备忘录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("已刷写：${flash.flashedPartition}")
            Text("备份 SHA-256：${flash.backupSha256}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            flash.backupExternalUri?.let { Text("备份外部位置：$it", style = MaterialTheme.typography.labelSmall) }
            flash.rescueExternalUri?.let { Text("Rescue Zip 外部位置：$it", style = MaterialTheme.typography.labelSmall) }

            flash.rollbackCommands.forEach { command ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(command, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { onCopy(command) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) {
                    Text("导出备份")
                }
                OutlinedButton(onClick = onExportRescue, modifier = Modifier.weight(1f)) {
                    Text("导出救援包")
                }
            }
            OutlinedButton(onClick = onScreenshot, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("截图保存备忘录")
            }
        }
    }
}

@Composable
private fun TerminalCard(logs: List<String>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("终端回显", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClear) { Text("清空") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp),
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

@Composable
private fun DangerousFlashDialog(
    targetHz: Int,
    partition: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var seconds by remember { mutableIntStateOf(5) }
    var confirmation by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
    }

    val semanticMatch = confirmation.trim() == targetHz.toString() || confirmation.trim() == "FLASH"
    val enabled = seconds == 0 && semanticMatch

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("高危操作：写入物理 DTBO 分区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标：$partition")
                Text("本应用只写当前目标槽位，不会同时覆盖 A/B。写入前会强制备份、SHA-256 校验并生成 Rescue Zip。")
                Text("请输入目标刷新率 $targetHz，或输入大写 FLASH：")
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (seconds > 0) "确认按钮将在 $seconds 秒后解锁" else "倒计时结束；仍需通过文本校验",
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled) {
                Text("确认单槽位刷写")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun captureWindowToPng(activity: Activity, uri: Uri) {
    val view = activity.window.decorView
    if (view.width <= 0 || view.height <= 0) {
        Toast.makeText(activity, "当前窗口尺寸无效", Toast.LENGTH_SHORT).show()
        return
    }

    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(
        activity.window,
        bitmap,
        { result ->
            if (result != PixelCopy.SUCCESS) {
                Toast.makeText(activity, "截图失败：PixelCopy=$result", Toast.LENGTH_SHORT).show()
                bitmap.recycle()
                return@request
            }

            (activity as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                val success = runCatching {
                    activity.contentResolver.openOutputStream(uri, "w")?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                }.getOrDefault(false)
                bitmap.recycle()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        if (success) "截图已保存" else "截图写入失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        },
        Handler(Looper.getMainLooper())
    )
}
