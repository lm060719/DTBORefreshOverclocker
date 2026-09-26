package io.mo.dtbooverclocker.ui

import android.app.Activity
import io.mo.dtbooverclocker.ui.theme.Spacing
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import io.mo.dtbooverclocker.ui.theme.AppTheme
import java.util.Locale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mo.dtbooverclocker.ui.components.DisclaimerDialog
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
    var flashViaModule by remember { mutableStateOf(false) }

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
    val studioPagerState = rememberPagerState { StudioTab.entries.size }
    val pageStateHolder = rememberSaveableStateHolder()
    val navigationScope = rememberCoroutineScope()

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
                onNavigateBack = {
                    navigationScope.launch { studioPagerState.scrollToPage(StudioTab.SETTINGS.ordinal) }
                    currentScreen = AppScreen.MAIN
                },
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
                onNavigateBack = {
                    navigationScope.launch { studioPagerState.scrollToPage(StudioTab.SETTINGS.ordinal) }
                    currentScreen = AppScreen.MAIN
                }
            )
        }
        AppScreen.MAIN -> {
            StudioScreen(
                state = state,
                pagerState = studioPagerState,
                pageStateHolder = pageStateHolder,
                navigation = NavigationActions(
                    onRefreshEnvironment = viewModel::refreshEnvironment,
                    onRequestRoot = viewModel::requestRoot,
                    onOpenRollback = { currentScreen = AppScreen.ROLLBACK },
                    onOpenAdvancedSettings = { currentScreen = AppScreen.SETTINGS },
                    onOpenAbout = { currentScreen = AppScreen.ABOUT }
                ),
                workspace = WorkspaceActions(
                    onImport = { openImage.launch(arrayOf("application/octet-stream", "*/*")) },
                    onExtract = viewModel::extractActivePartition,
                    onPackage = viewModel::packageStagedChanges,
                    onReset = viewModel::resetStagedChanges,
                    onUndoLastTransaction = viewModel::undoLastTransaction,
                    onUndoThroughTransaction = viewModel::undoThroughTransaction
                ),
                timing = TimingActions(
                    onSelect = viewModel::selectCandidate,
                    onTarget = viewModel::setTargetHz,
                    onStrategy = viewModel::setStrategy,
                    onPatchMode = viewModel::setPatchMode,
                    onCustomPixelClock = viewModel::setCustomPixelClock,
                    onCustomVfp = viewModel::setCustomVfp,
                    onCustomVbp = viewModel::setCustomVbp,
                    onCustomHfp = viewModel::setCustomHfp,
                    onCustomHbp = viewModel::setCustomHbp,
                    onApplySuggestedCustom = viewModel::applySuggestedCustomParams,
                    onStageChange = viewModel::stageTimingChange,
                    onStageCharging = viewModel::stageChargingChange
                ),
                deviceTree = DeviceTreeActions(
                    onSetProperty = viewModel::setDeviceTreeProperty,
                    onAddProperty = viewModel::addDeviceTreeProperty,
                    onDeleteProperty = viewModel::deleteDeviceTreeProperty,
                    onAddNode = viewModel::addDeviceTreeNode,
                    onCloneNode = viewModel::cloneDeviceTreeNode,
                    onRenameNode = viewModel::renameDeviceTreeNode,
                    onDeleteNode = viewModel::deleteDeviceTreeNode
                ),
                output = OutputActions(
                    onSavePatched = { file ->
                        pendingBinary = file
                        saveBinary.launch(file.name)
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
                    onModuleZip = {
                        viewModel.prepareModuleZip { file ->
                            pendingZip = file
                            saveZip.launch(file.name)
                        }
                    },
                    onFlash = {
                        flashViaModule = false
                        showFlashDialog = true
                    },
                    onFlashModule = {
                        flashViaModule = true
                        showFlashDialog = true
                    },
                    onExportBackup = { file ->
                        pendingBinary = file
                        saveBinary.launch(file.name)
                    },
                    onExportRescue = { file ->
                        pendingZip = file
                        saveZip.launch(file.name)
                    },
                    onScreenshot = {
                        saveScreenshot.launch("DTBO_rescue_memo_${System.currentTimeMillis()}.png")
                    },
                    onCopy = { text -> copyText(context, "DTBO rollback", text) },
                    onClearLogs = viewModel::clearLogs
                )
            )
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
                    modifier = Modifier.padding(Spacing.xl),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(state.status)
                }
            }
        }
    }

    if (!state.isDisclaimerAccepted) {
        DisclaimerDialog(
            isFirstLaunch = true,
            onConfirm = viewModel::acceptDisclaimer,
            onExit = { activity.finish() }
        )
    }

    if (showFlashDialog) {
        DangerousFlashDialog(
            targetHz = state.targetHz,
            partition = state.slotInfo?.blockDevice.orEmpty(),
            viaModule = flashViaModule,
            onDismiss = { showFlashDialog = false },
            onConfirm = {
                showFlashDialog = false
                viewModel.flashPatched(viaModule = flashViaModule)
            }
        )
    }
}

@Composable
private fun DangerousFlashDialog(
    targetHz: Int,
    partition: String,
    viaModule: Boolean,
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
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("目标：$partition")
                Text("本应用只写当前目标槽位。写入前会强制备份、SHA-256 校验并生成 Rescue Zip。")
                if (viaModule) {
                    Text("将打包为模块并交给 KernelSU / Magisk / APatch 安装，由模块完成写入；移除模块并重启会自动写回原 DTBO。")
                }
                Text("请输入目标刷新率 $targetHz，或输入大写 FLASH：")
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (seconds > 0) {
                    Text(
                        "确认按钮将在 $seconds 秒后解锁",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled) {
                Text(if (viaModule) "确认以模块刷入" else "确认单槽位刷写")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
