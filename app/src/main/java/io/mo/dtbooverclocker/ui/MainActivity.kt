package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.ui.components.dangerButtonColors
import io.mo.dtbooverclocker.ui.components.NoticeBanner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.widthIn
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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import io.mo.dtbooverclocker.model.AppLanguage
import io.mo.dtbooverclocker.ui.i18n.AppStrings
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.i18n.LocalStrings
import io.mo.dtbooverclocker.util.LocaleHelper

enum class AppScreen {
    MAIN,
    ROLLBACK,
    SETTINGS,
    ABOUT
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
        val lang = AppLanguage.fromCode(prefs.getString("app_language", null))
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current
            val strings = remember(state.appLanguage) { I18n.getStrings(state.appLanguage) }
            val currentLocale = remember(state.appLanguage) { LocaleHelper.getEffectiveLocale(state.appLanguage) }

            LaunchedEffect(state.appLanguage) {
                LocaleHelper.updateSystemLocale(context, state.appLanguage)
            }

            val configuration = LocalConfiguration.current
            val localizedConfiguration = remember(configuration, currentLocale) {
                android.content.res.Configuration(configuration).apply {
                    setLocale(currentLocale)
                }
            }
            val localizedContext = remember(context, currentLocale) {
                context.createConfigurationContext(localizedConfiguration)
            }

            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalConfiguration provides localizedConfiguration,
                LocalContext provides localizedContext
            ) {
                AppTheme {
                    DtboOverclockerApp(viewModel)
                }
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
    val strings = I18n.current

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
        if (uri != null) captureWindowToPng(activity, uri, strings)
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
                onRefreshEnvironment = viewModel::refreshEnvironment,
                onRefreshCacheSize = viewModel::refreshCacheSize,
                onClearAllCache = viewModel::clearAllCache,
                onRefreshLogStats = viewModel::refreshLogStats,
                onExportLogs = {
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    saveLogs.launch("DTBO_Log_${timestamp}.txt")
                },
                onClearAllLogs = viewModel::clearLogFiles,
                onSetLanguage = viewModel::setAppLanguage
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
                    onCopy = { text -> copyText(context, "DTBO rollback", text, strings.copied) },
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
            Card(
                modifier = Modifier.padding(Spacing.xl).widthIn(max = 320.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 4.dp
                    )
                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
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
    val strings = I18n.current
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
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(strings.flashDangerousTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(
                        strings.flashTarget(partition),
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(strings.flashWarningBody, style = MaterialTheme.typography.bodyMedium)
                if (viaModule) {
                    NoticeBanner(strings.flashViaModuleNotice)
                }
                Text(strings.flashConfirmPrompt(targetHz), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    singleLine = true,
                    isError = confirmation.isNotBlank() && !semanticMatch,
                    modifier = Modifier.fillMaxWidth()
                )
                if (seconds > 0) {
                    Text(
                        strings.flashButtonCountdown(seconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled, colors = dangerButtonColors()) {
                Text(if (viaModule) strings.confirmModuleFlash else strings.confirmSlotFlash)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

private fun copyText(context: Context, label: String, text: String, tip: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
}

private fun captureWindowToPng(activity: Activity, uri: Uri, strings: AppStrings) {
    val view = activity.window.decorView
    if (view.width <= 0 || view.height <= 0) {
        Toast.makeText(activity, strings.windowSizeInvalid, Toast.LENGTH_SHORT).show()
        return
    }

    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(
        activity.window,
        bitmap,
        { result ->
            if (result != PixelCopy.SUCCESS) {
                Toast.makeText(activity, strings.screenshotFailed(result), Toast.LENGTH_SHORT).show()
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
                        if (success) strings.screenshotSaved else strings.screenshotWriteFailed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        },
        Handler(Looper.getMainLooper())
    )
}
