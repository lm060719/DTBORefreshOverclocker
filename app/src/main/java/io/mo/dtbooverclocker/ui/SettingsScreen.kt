package io.mo.dtbooverclocker.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import io.mo.dtbooverclocker.ui.components.Button
import androidx.compose.material3.ButtonDefaults
import io.mo.dtbooverclocker.ui.components.AppCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import io.mo.dtbooverclocker.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import io.mo.dtbooverclocker.ui.components.OutlinedButton
import io.mo.dtbooverclocker.ui.components.RadioButton
import io.mo.dtbooverclocker.ui.components.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.mo.dtbooverclocker.ui.components.TextButton
import io.mo.dtbooverclocker.ui.components.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import io.mo.dtbooverclocker.ui.components.appBarScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.AppLanguage
import io.mo.dtbooverclocker.ui.components.ThemeSettingsEntry
import io.mo.dtbooverclocker.ui.components.LanguagePreference
import io.mo.dtbooverclocker.ui.components.PreferenceIcon
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing
import io.mo.dtbooverclocker.util.StorageUtils
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onNavigateBack: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onRefreshCacheSize: () -> Unit,
    onClearAllCache: (onCleared: (Long) -> Unit) -> Unit,
    onRefreshLogStats: () -> Unit,
    onExportLogs: () -> Unit,
    onClearAllLogs: (onCleared: () -> Unit) -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
    onOpenThemeSettings: () -> Unit,
    backHandlerEnabled: Boolean = true
) {
    BackHandler(enabled = backHandlerEnabled, onBack = onNavigateBack)
    val strings = I18n.current
    val miuix = AppTheme.isMiuix
    val context = LocalContext.current
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()

    LaunchedEffect(Unit) {
        onRefreshCacheSize()
        onRefreshLogStats()
    }

    Scaffold(
        modifier = Modifier.testTag("advanced-settings-screen").appBarScroll(scrollBehavior),
        topBar = {
            TopAppBar(
                title = strings.settingsTitle,
                scrollBehavior = scrollBehavior,
                titlePadding = Spacing.title,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.backToHome
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshEnvironment) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.refreshEnvironmentProbe)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.section)
        ) {
            item { Spacer(Modifier.height(if (AppTheme.isMiuix) 0.dp else 4.dp)) }
            item(key = "theme") { ThemeSettingsEntry(onOpenThemeSettings) }
            if (miuix) {
                item(key = "language") {
                    AppCard(Modifier.fillMaxWidth()) {
                        LanguagePreference(state.appLanguage, onSetLanguage)
                    }
                }
                item(key = "cache") {
                    AppCard(Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = strings.appCache,
                            summary = strings.appCacheDesc,
                            startAction = { PreferenceIcon(Icons.Default.CleaningServices) },
                            endActions = { Text(StorageUtils.formatFileSize(state.cacheSizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                        ArrowPreference(
                            title = strings.clearAllCache,
                            startAction = { PreferenceIcon(Icons.Default.DeleteOutline) },
                            onClick = { showClearCacheDialog = true }
                        )
                    }
                }
                item(key = "logs") {
                    AppCard(Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = strings.runtimeLogs,
                            summary = strings.logFilesStats(state.logFilesCount, StorageUtils.formatFileSize(state.logFilesSizeBytes)),
                            startAction = { PreferenceIcon(Icons.AutoMirrored.Filled.Article) }
                        )
                        ArrowPreference(
                            title = strings.exportFullLogs,
                            startAction = { PreferenceIcon(Icons.Default.FileDownload) },
                            onClick = onExportLogs
                        )
                        ArrowPreference(
                            title = strings.clearLogs,
                            startAction = { PreferenceIcon(Icons.Default.DeleteOutline) },
                            onClick = { showClearLogsDialog = true }
                        )
                    }
                }
            } else {
                // 1. Language Settings Section
                item {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                ) {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        strings.settingsLanguage,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = when (state.appLanguage) {
                                            AppLanguage.FOLLOW_SYSTEM -> strings.langFollowSystem
                                            AppLanguage.ENGLISH -> strings.langEnglish
                                            AppLanguage.CHINESE -> strings.langChinese
                                        },
                                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Text(
                                text = strings.settingsLanguageDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Column(
                                modifier = Modifier.fillMaxWidth().selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                val languageOptions = listOf(
                                    AppLanguage.FOLLOW_SYSTEM to strings.langFollowSystem,
                                    AppLanguage.ENGLISH to strings.langEnglish,
                                    AppLanguage.CHINESE to strings.langChinese
                                )

                                languageOptions.forEach { (lang, label) ->
                                    val selected = state.appLanguage == lang
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSetLanguage(lang) }),
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.surface
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Spacing.md, vertical = Spacing.lg),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                        ) {
                                            RadioButton(
                                                selected = selected,
                                                onClick = null
                                            )
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Cache Management Section
                item {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                ) {
                                    Icon(
                                        Icons.Default.CleaningServices,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        strings.appCache,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = StorageUtils.formatFileSize(state.cacheSizeBytes),
                                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Text(
                                text = strings.appCacheDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { showClearCacheDialog = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.clearAllCache)
                                }
                            }
                        }
                    }
                }

                // 3. Log Management Section
                item {
                    AppCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Article,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        strings.runtimeLogs,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = strings.logFilesStats(state.logFilesCount, StorageUtils.formatFileSize(state.logFilesSizeBytes)),
                                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Button(
                                    onClick = onExportLogs,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.FileDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.exportFullLogs)
                                }

                                OutlinedButton(
                                    onClick = { showClearLogsDialog = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.clearLogs)
                                }
                            }
                        }
                    }
                }

            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Clear Cache Confirmation Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = {
                Icon(
                    Icons.Default.CleaningServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(strings.confirmClearCacheTitle) },
            text = {
                Text(strings.confirmClearCacheBody(StorageUtils.formatFileSize(state.cacheSizeBytes)))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheDialog = false
                        onClearAllCache { freedBytes ->
                            val message = if (freedBytes > 0) {
                                strings.cacheClearedFreed(StorageUtils.formatFileSize(freedBytes))
                            } else {
                                strings.cacheCleared
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(strings.clearAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // Clear Logs Confirmation Dialog
    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(strings.confirmClearLogsTitle) },
            text = {
                Text(strings.confirmClearLogsBody(state.logFilesCount, StorageUtils.formatFileSize(state.logFilesSizeBytes)))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearLogsDialog = false
                        onClearAllLogs {
                            Toast.makeText(context, strings.logsCleared, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(strings.clearAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}
