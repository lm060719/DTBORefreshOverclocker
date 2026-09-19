package io.mo.dtbooverclocker.ui

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.graphics.Color
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

    var showManualBackupDialog by remember { mutableStateOf(false) }
    var pendingFlashRecord by remember { mutableStateOf<BackupRecord?>(null) }
    var pendingDeleteRecord by remember { mutableStateOf<BackupRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("镜像回滚", fontWeight = FontWeight.SemiBold)
                        Text(
                            "DTBO 分区备份时间轴与还原",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回主页"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showManualBackupDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "手动备份当前分区")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新备份列表")
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
                    .padding(24.dp),
                onManualBackupClick = { showManualBackupDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                item {
                    RollbackHeaderCard(
                        totalCount = state.backups.size,
                        slotLabel = state.slotInfo?.label ?: "未知槽位",
                        blockDevice = state.slotInfo?.blockDevice ?: "未知分区",
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
                            Toast.makeText(context, "MD5 已复制到剪贴板", Toast.LENGTH_SHORT).show()
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
            title = { Text("手动备份当前 DTBO 镜像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将通过 Root 读取当前活跃分区 (${state.slotInfo?.blockDevice ?: "未检测到槽位"}) 并保存为回滚镜像。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = manualDesc,
                        onValueChange = { manualDesc = it },
                        label = { Text("备份说明备注（可选）") },
                        placeholder = { Text("例如：刷入 144Hz 前的原厂基准") },
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
                    Text("立即备份")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualBackupDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Dangerous Flash Rollback Confirmation Dialog
    pendingFlashRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingFlashRecord = null },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "高风险警示",
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    "确认回滚刷入 DTBO 镜像？",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "您即将把选定的备份镜像物理写入设备分区，此操作将覆盖当前的 DTBO 分区！",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("• 目标分区：${state.slotInfo?.blockDevice ?: record.blockDevice}", style = MaterialTheme.typography.bodySmall)
                            Text("• 备份文件：${record.fileName}", style = MaterialTheme.typography.bodySmall)
                            Text("• 备份时间：${record.formattedTime}", style = MaterialTheme.typography.bodySmall)
                            Text("• 备份系统：${record.androidVersion}", style = MaterialTheme.typography.bodySmall)
                            Text("• 系统版本：${record.buildDisplay}", style = MaterialTheme.typography.bodySmall)
                            Text("• 记录 MD5：${record.recordedMd5}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(
                        "写入后系统将自动进行写后回读 MD5 校验以确保完整性。请确保电量充足，刷写过程中请勿断电或重启手机。",
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
                    Text("确认回滚刷入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFlashRecord = null }) {
                    Text("取消")
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
                    contentDescription = "删除确认",
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("删除此备份？") },
            text = {
                Text("确定要删除镜像 ${record.fileName} 吗？删除后本地文件与元数据将永久移除，无法再用于一键回滚。")
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
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecord = null }) {
                    Text("取消")
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("备份镜像库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "当前槽位: $slotLabel ($blockDevice)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "共 $totalCount 个备份",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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
                Text("手动备份当前手机 DTBO 镜像")
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
                    contentDescription = record.backupType.displayName,
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
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header: Tag + Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (record.backupType == BackupType.AUTO) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = record.backupType.displayName,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            InfoRow(label = "系统版本", value = record.androidVersion)
                            InfoRow(label = "系统固件", value = record.buildDisplay)
                            InfoRow(label = "设备机型", value = record.deviceModel)
                            InfoRow(label = "备份槽位", value = "${record.slot} (${record.blockDevice})")
                            InfoRow(label = "文件大小", value = StorageUtils.formatFileSize(record.fileSizeBytes))
                        }
                    }

                    // MD5 Verification Section
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                        contentDescription = "复制 MD5",
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
                                            text = "未校验完整性",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    BackupVerificationStatus.VERIFYING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Text(
                                                text = "正在校验 MD5…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    BackupVerificationStatus.MATCHED -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "MD5 校验通过 (一致)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                    BackupVerificationStatus.MISMATCH -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Error,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = verificationState.message ?: "MD5 不一致",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    BackupVerificationStatus.FILE_MISSING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "备份镜像文件已丢失",
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
                                    Text("验证 MD5", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // 3 Actions: Export, Flash, Delete
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onExport,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出", style = MaterialTheme.typography.labelSmall)
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
                            Text("刷入", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
                            Text("删除", style = MaterialTheme.typography.labelSmall)
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
            text = "暂无备份镜像",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在直接刷入超频镜像前，系统会自动备份当前活跃槽位分区；您也可以随时手动备份当前手机 DTBO 分区以便日后回滚。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onManualBackupClick) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("立即手动备份当前镜像")
        }
    }
}

