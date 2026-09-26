package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import io.mo.dtbooverclocker.ui.theme.AppTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.AvbProtectionState
import io.mo.dtbooverclocker.ui.components.PanelClassification
import io.mo.dtbooverclocker.ui.components.TimingUtils

@Composable
internal fun SourceCard(
    state: MainUiState,
    onImport: () -> Unit,
    onExtract: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("镜像来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("手动导入")
                }
                Button(
                    onClick = onExtract,
                    enabled = state.rootState.suPresent,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("提取当前分区")
                }
            }

            if (!state.rootState.suPresent) {
                Text(
                    "未检测到 Root 权限，可点击“手动导入”选择外部 dtbo.img 文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "手动导入支持外部镜像（免 Root）；提取当前分区只读取 ${state.slotInfo?.blockDevice ?: "当前 dtbo"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ImageSummaryCard(state: MainUiState) {
    val workspace = state.workspace ?: return
    val groups = remember(workspace.candidates) {
        TimingUtils.groupCandidates(workspace.candidates)
    }
    val vendorCount = remember(groups) {
        groups.keys.count { it.classification == PanelClassification.VENDOR }
    }
    val referenceCount = remember(groups) {
        groups.keys.count { it.classification == PanelClassification.QCOM_REFERENCE }
    }
    val simulationCount = remember(groups) {
        groups.keys.count { it.classification == PanelClassification.SIMULATION }
    }
    val unknownCount = remember(groups) {
        groups.keys.count { it.classification == PanelClassification.UNKNOWN }
    }
    val panelCount = groups.size
    val panelInstanceCount = remember(workspace.candidates) {
        workspace.candidates
            .map { it.entryIndex to TimingUtils.parsePanelIdentifier(it.nodePath) }
            .distinct()
            .size
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("镜像解析结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        "DTBO v${workspace.metadata.version}",
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("DTB: ${workspace.metadata.entries.size}") }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("唯一面板: $panelCount") }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("面板 DTB 实例: $panelInstanceCount") }
                )
                if (vendorCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("厂商面板: $vendorCount") }
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text("时序候选: ${workspace.candidates.size}") }
                )
                workspace.sourceImage?.let { source ->
                    AssistChip(
                        onClick = {},
                        label = {
                            val avbLabel = when (source.avbProtectionState) {
                                AvbProtectionState.NONE -> "AVB: 无"
                                AvbProtectionState.UNSIGNED -> "AVB: 未签名"
                                AvbProtectionState.SIGNED -> {
                                    "AVB: 已签名${source.avbAlgorithm?.let { " $it" }.orEmpty()}"
                                }
                            }
                            Text(avbLabel)
                        },
                        leadingIcon = if (source.avbProtectionState == AvbProtectionState.SIGNED) {
                            {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
                if (state.activePanelDisplayName != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("在用: ${state.activePanelDisplayName}") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AppTheme.status.success,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }

            Text(
                "面板统计按唯一 panel identifier 去重；同一面板出现在多个 DTB entry 时只算 1 个唯一面板。" +
                    " 当前分类：厂商 $vendorCount / 高通参考 $referenceCount / 仿真 $simulationCount / 未分类 $unknownCount。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (state.activePanelDisplayName != null) {
                Text(
                    "已通过设备运行信息优先标记当前在用面板；“厂商面板”只做正向识别，未知标识不会再自动算作机型专属。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
internal fun OutputCard(
    state: MainUiState,
    onSavePatched: () -> Unit,
    onRecoveryZip: () -> Unit,
    onFastbootBundle: () -> Unit,
    onModuleZip: () -> Unit,
    onFlash: () -> Unit,
    onFlashModule: () -> Unit
) {
    val report = state.patchReport ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("输出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val modeTitle = if (state.transactions.size == 1) {
                val transaction = state.transactions.single()
                "${transaction.kind.displayName}事务完成 · ${transaction.operationCount} 个底层操作 · ${transaction.risk.displayName}"
            } else {
                "事务打包完成：${state.transactions.size} 个事务 / ${state.transactions.sumOf { it.operationCount }} 个底层操作"
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
            OutlinedButton(onClick = onModuleZip, modifier = Modifier.fillMaxWidth()) {
                Text("导出 KernelSU / Magisk 模块")
            }

            val canFlash = state.rootState.granted && state.transactions.isNotEmpty()
            Button(
                onClick = onFlash,
                enabled = canFlash,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FlashOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("直接刷写当前槽位")
            }
            Button(
                onClick = onFlashModule,
                enabled = canFlash,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FlashOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("制作成模块并刷入")
            }
            Text(
                "模块方式通过 KernelSU / Magisk / APatch 安装，安装时写入当前槽位；在管理器中移除模块并重启即可自动恢复原 DTBO。",
                style = MaterialTheme.typography.labelSmall
            )
            if (!canFlash) {
                Text(
                    "直接刷写需要 Root 授权。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
internal fun RescueMemoCard(
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
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                        Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(command, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { onCopy(command) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
internal fun TerminalCard(logs: List<String>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("终端回显", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClear) { Text("清空") }
            }
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(Spacing.md),
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
}
