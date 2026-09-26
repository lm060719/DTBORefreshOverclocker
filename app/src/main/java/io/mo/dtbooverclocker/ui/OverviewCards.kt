package io.mo.dtbooverclocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.AvbProtectionState
import io.mo.dtbooverclocker.ui.components.HintText
import io.mo.dtbooverclocker.ui.components.IconLabel
import io.mo.dtbooverclocker.ui.components.KeyValueRow
import io.mo.dtbooverclocker.ui.components.NoticeBanner
import io.mo.dtbooverclocker.ui.components.PanelClassification
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.StatusPill
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.ui.components.Tone
import io.mo.dtbooverclocker.ui.components.dangerButtonColors
import io.mo.dtbooverclocker.ui.theme.Spacing

private enum class StepState { DONE, CURRENT, PENDING }

/** 概览页顶部：环境状态 + 导入→识别→修改→打包→导出 流程进度。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkflowCard(state: MainUiState) {
    val done = listOf(
        state.workspace != null,
        state.workspace != null && !state.capabilityScanInProgress && state.capabilityReport != null,
        state.transactions.isNotEmpty(),
        state.patchReport != null,
        state.lastFlash != null
    )
    val current = done.indexOfFirst { !it }
    val labels = listOf("导入", "识别", "修改", "打包", "导出")

    SectionCard(tone = Tone.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text("DTBO Studio", style = MaterialTheme.typography.titleLarge)
            HintText("导入、分析、编辑、验证并重新构建 DTBO。")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state.rootState.granted) {
                StatusPill("Root ✓", tone = Tone.Success, icon = Icons.Default.LockOpen)
            } else {
                StatusPill("免 Root 可用", icon = Icons.Default.Lock)
            }
            state.slotInfo?.let { StatusPill(it.label, icon = Icons.Default.Memory) }
            if (state.workspace != null) {
                StatusPill("工作区已加载", tone = Tone.Primary, icon = Icons.Default.CheckCircle)
            } else {
                StatusPill("等待镜像", icon = Icons.Default.FolderOpen)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            labels.forEachIndexed { index, label ->
                val stepState = when {
                    done[index] -> StepState.DONE
                    index == current -> StepState.CURRENT
                    else -> StepState.PENDING
                }
                WorkflowStep(index + 1, label, stepState, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WorkflowStep(number: Int, label: String, stepState: StepState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (stepState) {
        StepState.DONE -> scheme.primary to scheme.onPrimary
        StepState.CURRENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        StepState.PENDING -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (stepState == StepState.DONE) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                } else {
                    Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (stepState == StepState.PENDING) scheme.onSurfaceVariant else scheme.onSurface,
            fontWeight = if (stepState == StepState.CURRENT) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
internal fun SourceCard(
    state: MainUiState,
    onImport: () -> Unit,
    onExtract: () -> Unit
) {
    SectionCard(title = "镜像来源", icon = Icons.Default.FolderOpen) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                IconLabel(Icons.Default.FolderOpen, "手动导入")
            }
            FilledTonalButton(
                onClick = onExtract,
                enabled = state.rootState.suPresent,
                modifier = Modifier.weight(1f)
            ) {
                IconLabel(Icons.Default.Save, "提取当前分区")
            }
        }
        if (!state.rootState.suPresent) {
            HintText("未检测到 Root 权限，可点击“手动导入”选择外部 dtbo.img 文件。")
        } else {
            HintText("手动导入支持外部镜像（免 Root）；提取当前分区只读取 ${state.slotInfo?.blockDevice ?: "当前 dtbo"}")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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

    SectionCard(
        title = "镜像解析结果",
        icon = Icons.Default.Inventory2,
        trailing = { StatusPill("DTBO v${workspace.metadata.version}") }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MetricTile("DTB", "${workspace.metadata.entries.size}", Modifier.weight(1f))
            MetricTile("唯一面板", "$panelCount", Modifier.weight(1f))
            MetricTile("时序候选", "${workspace.candidates.size}", Modifier.weight(1f))
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            StatusPill("面板 DTB 实例: $panelInstanceCount")
            if (vendorCount > 0) {
                StatusPill("厂商面板: $vendorCount")
            }
            workspace.sourceImage?.let { source ->
                val avbLabel = when (source.avbProtectionState) {
                    AvbProtectionState.NONE -> "AVB: 无"
                    AvbProtectionState.UNSIGNED -> "AVB: 未签名"
                    AvbProtectionState.SIGNED -> "AVB: 已签名${source.avbAlgorithm?.let { " $it" }.orEmpty()}"
                }
                StatusPill(
                    avbLabel,
                    icon = if (source.avbProtectionState == AvbProtectionState.SIGNED) Icons.Default.Lock else null
                )
            }
            if (state.activePanelDisplayName != null) {
                StatusPill("在用: ${state.activePanelDisplayName}", tone = Tone.Success, icon = Icons.Default.CheckCircle)
            }
        }

        HintText(
            "面板统计按唯一 panel identifier 去重；同一面板出现在多个 DTB entry 时只算 1 个唯一面板。" +
                " 当前分类：厂商 $vendorCount / 高通参考 $referenceCount / 仿真 $simulationCount / 未分类 $unknownCount。"
        )
        if (state.activePanelDisplayName != null) {
            HintText("已通过设备运行信息优先标记当前在用面板；“厂商面板”只做正向识别，未知标识不会再自动算作机型专属。")
        }
        Text(
            workspace.inputImage.name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val modeTitle = if (state.transactions.size == 1) {
        val transaction = state.transactions.single()
        "${transaction.kind.displayName}事务完成 · ${transaction.operationCount} 个底层操作 · ${transaction.risk.displayName}"
    } else {
        "事务打包完成：${state.transactions.size} 个事务 / ${state.transactions.sumOf { it.operationCount }} 个底层操作"
    }
    SectionCard(title = "输出", subtitle = modeTitle, icon = Icons.Default.Build, tone = Tone.Success) {
        if (report.changes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                report.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        report.warnings.forEach { NoticeBanner(it, tone = Tone.Danger) }

        Text("导出", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSavePatched, modifier = Modifier.fillMaxWidth()) {
            IconLabel(Icons.Default.Save, "保存 dtbo_patched.img")
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

        HorizontalDivider(Modifier.padding(vertical = Spacing.xs))

        val canFlash = state.rootState.granted && state.transactions.isNotEmpty()
        Text("刷写到设备", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = onFlash,
            enabled = canFlash,
            colors = dangerButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            IconLabel(Icons.Default.FlashOn, "直接刷写当前槽位")
        }
        Button(
            onClick = onFlashModule,
            enabled = canFlash,
            colors = dangerButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            IconLabel(Icons.Default.FlashOn, "制作成模块并刷入")
        }
        HintText("模块方式通过 KernelSU / Magisk / APatch 安装，安装时写入当前槽位；在管理器中移除模块并重启即可自动恢复原 DTBO。")
        if (!canFlash) {
            NoticeBanner("直接刷写需要 Root 授权。", tone = Tone.Warning)
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
    SectionCard(title = "救砖备忘录", icon = Icons.Default.Warning, tone = Tone.Danger) {
        KeyValueRow("已刷写", flash.flashedPartition, monospace = true)
        KeyValueRow("备份 SHA-256", flash.backupSha256, monospace = true)
        flash.backupExternalUri?.let { KeyValueRow("备份外部位置", it) }
        flash.rescueExternalUri?.let { KeyValueRow("Rescue Zip 外部位置", it) }

        flash.rollbackCommands.forEach { command ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        command,
                        Modifier.weight(1f).padding(vertical = Spacing.md),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
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
            IconLabel(Icons.Default.Image, "截图保存备忘录")
        }
    }
}

/** 终端回显：默认折叠只显示最后一行，展开后显示完整滚动日志。 */
@Composable
internal fun TerminalCard(logs: List<String>, onClear: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size, expanded) {
        if (expanded && logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    SectionCard(
        title = "终端回显",
        subtitle = "${logs.size} 行",
        icon = Icons.Default.Terminal,
        modifier = Modifier.clickable { expanded = !expanded },
        trailing = {
            TextButton(onClick = onClear) { Text("清空") }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        if (!expanded) {
            logs.lastOrNull()?.let { last ->
                Text(
                    last,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
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

