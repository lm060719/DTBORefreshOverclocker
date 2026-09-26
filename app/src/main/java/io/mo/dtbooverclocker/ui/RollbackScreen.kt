package io.mo.dtbooverclocker.ui

import android.widget.Toast
import io.mo.dtbooverclocker.ui.theme.Spacing
import io.mo.dtbooverclocker.ui.theme.AppTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.BackupRecord
import io.mo.dtbooverclocker.model.BackupType
import io.mo.dtbooverclocker.model.BackupVerificationState
import io.mo.dtbooverclocker.model.BackupVerificationStatus
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.util.StorageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollbackScreen(
    state: MainUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onManualBackup: (description: String) -> Unit,
    onVerifyMd5: (record: BackupRecord) -> Unit,
    onExportBackup: (record: BackupRecord) -> Unit,
    onFlashBackup: (record: BackupRecord) -> Unit,
    onDeleteBackup: (record: BackupRecord) -> Unit
) {
    BackHandler(onBack = onNavigateBack)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val strings = I18n.current

    var showManualBackupDialog by remember { mutableStateOf(false) }
    var pendingFlashRecord by remember { mutableStateOf<BackupRecord?>(null) }
    var pendingDeleteRecord by remember { mutableStateOf<BackupRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.rollbackTitle, fontWeight = FontWeight.SemiBold)
                        Text(
                            strings.rollbackSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.cancel
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showManualBackupDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = strings.manualBackup)
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.refreshBackups)
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.backups.isEmpty()) {
            EmptyRollbackState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(Spacing.xl),
                onManualBackupClick = { showManualBackupDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = Spacing.lg),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                item {
                    RollbackHeaderCard(
                        totalCount = state.backups.size,
                        slotLabel = state.slotInfo?.label ?: "Unknown",
                        blockDevice = state.slotInfo?.blockDevice ?: "dtbo",
                        onManualBackupClick = { showManualBackupDialog = true }
                    )
                    Spacer(Modifier.height(16.dp))
                }

                itemsIndexed(state.backups, key = { _, item -> item.id }) { index, record ->
                    val isLast = index == state.backups.lastIndex
                    val verificationState = state.backupVerificationStates[record.id]
                        ?: BackupVerificationState()

                    TimelineBackupItem(
                        record = record,
                        isLast = isLast,
                        verificationState = verificationState,
                        onVerifyMd5 = { onVerifyMd5(record) },
                        onCopyMd5 = {
                            clipboard.setText(AnnotatedString(record.recordedMd5))
                            Toast.makeText(context, strings.md5Copied, Toast.LENGTH_SHORT).show()
                        },
                        onExport = { onExportBackup(record) },
                        onFlash = { pendingFlashRecord = record },
                        onDelete = { pendingDeleteRecord = record }
                    )
                }
            }
        }
    }

    // Manual Backup Dialog
    if (showManualBackupDialog) {
        var manualDesc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualBackupDialog = false },
            title = { Text(strings.manualBackupTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        strings.manualBackupDialogBody(state.slotInfo?.blockDevice ?: "dtbo"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = manualDesc,
                        onValueChange = { manualDesc = it },
                        label = { Text(strings.backupDescLabel) },
                        placeholder = { Text(strings.backupDescPlaceholder) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showManualBackupDialog = false
                        onManualBackup(manualDesc)
                    }
                ) {
                    Text(strings.backupNow)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualBackupDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // Dangerous Flash Rollback Confirmation Dialog
    // Dangerous Flash Rollback Confirmation Dialog
    pendingFlashRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingFlashRecord = null },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    strings.flashThisBackupTitle,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        strings.confirmRollbackFlashWarning,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(strings.rollbackTargetPartition(state.slotInfo?.blockDevice ?: record.blockDevice), style = MaterialTheme.typography.bodySmall)
                            Text(strings.rollbackBackupFile(record.fileName), style = MaterialTheme.typography.bodySmall)
                            Text(strings.rollbackBackupTime(record.formattedTime), style = MaterialTheme.typography.bodySmall)
                            Text(strings.rollbackAndroidVersion(record.androidVersion), style = MaterialTheme.typography.bodySmall)
                            Text(strings.rollbackBuildDisplay(record.buildDisplay), style = MaterialTheme.typography.bodySmall)
                            Text(strings.rollbackRecordedMd5(record.recordedMd5), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(
                        strings.rollbackVerifyNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = record
                        pendingFlashRecord = null
                        onFlashBackup(target)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(strings.confirmRollbackFlashBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFlashRecord = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // Delete Confirmation Dialog
    pendingDeleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecord = null },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(strings.deleteBackupTitle) },
            text = {
                Text(strings.deleteBackupBody(record.fileName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = record
                        pendingDeleteRecord = null
                        onDeleteBackup(target)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(strings.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecord = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
private fun RollbackHeaderCard(
    totalCount: Int,
    slotLabel: String,
    blockDevice: String,
    onManualBackupClick: () -> Unit
) {
    val strings = I18n.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
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
                Column {
                    Text(strings.backupRepo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        strings.currentSlotSubtitle(slotLabel, blockDevice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = strings.totalBackupsCount(totalCount),
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            FilledTonalButton(
                onClick = onManualBackupClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.manualBackupCurrentPartition)
            }
        }
    }
}

@Composable
private fun TimelineBackupItem(
    record: BackupRecord,
    isLast: Boolean,
    verificationState: BackupVerificationState,
    onVerifyMd5: () -> Unit,
    onCopyMd5: () -> Unit,
    onExport: () -> Unit,
    onFlash: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = I18n.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Left timeline track (Icon node + Connecting line)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight()
        ) {
            // Milestone node
            val isAuto = record.backupType == BackupType.AUTO
            val nodeColor = if (isAuto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            val nodeIcon = if (isAuto) Icons.Default.AutoAwesome else Icons.Default.TouchApp

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(nodeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    nodeIcon,
                    contentDescription = record.backupType.getDisplayName(strings),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Connecting line
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Right content card
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 12.dp else 20.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    // Header: Tag + Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (record.backupType == BackupType.AUTO) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = record.backupType.getDisplayName(strings),
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (record.backupType == BackupType.AUTO) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }

                        Text(
                            text = record.formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // System info
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(
                            text = record.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (record.description.isNotBlank()) {
                            Text(
                                text = record.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Metadata details box
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                        ) {
                            InfoRow(label = strings.infoRowAndroidVersion, value = record.androidVersion)
                            InfoRow(label = strings.infoRowBuildDisplay, value = record.buildDisplay)
                            InfoRow(label = strings.infoRowDeviceModel, value = record.deviceModel)
                            InfoRow(label = strings.infoRowBackupSlot, value = "${record.slot} (${record.blockDevice})")
                            InfoRow(label = strings.infoRowFileSize, value = StorageUtils.formatFileSize(record.fileSizeBytes))
                        }
                    }

                    // MD5 Verification Section
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "MD5: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = record.recordedMd5,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                IconButton(
                                    onClick = onCopyMd5,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "${strings.copy} MD5",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // MD5 Status Badge & Verification Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (verificationState.status) {
                                    BackupVerificationStatus.UNCHECKED -> {
                                        Text(
                                            text = strings.md5Unchecked,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    BackupVerificationStatus.VERIFYING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Text(
                                                text = strings.md5Verifying,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    BackupVerificationStatus.MATCHED -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = AppTheme.status.success,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = strings.md5Matched,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AppTheme.status.success
                                            )
                                        }
                                    }
                                    BackupVerificationStatus.MISMATCH -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            Icon(
                                                Icons.Default.Error,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = verificationState.message ?: strings.md5Mismatch,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    BackupVerificationStatus.FILE_MISSING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = strings.md5FileMissing,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }

                                OutlinedButton(
                                    onClick = onVerifyMd5,
                                    enabled = verificationState.status != BackupVerificationStatus.VERIFYING,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(strings.verifyMd5, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // 3 Actions: Export, Flash, Delete
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onExport,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.export, style = MaterialTheme.typography.labelSmall)
                        }

                        Button(
                            onClick = onFlash,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.flash, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.delete, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyRollbackState(
    modifier: Modifier = Modifier,
    onManualBackupClick: () -> Unit
) {
    val strings = I18n.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.HistoryEdu,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = strings.noBackups,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = strings.noBackupsHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.lg)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onManualBackupClick) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(strings.emptyBackupsBtn)
        }
    }
}

