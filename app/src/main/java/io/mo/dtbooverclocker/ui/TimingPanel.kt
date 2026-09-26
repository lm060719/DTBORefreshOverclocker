package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import java.util.Locale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.ui.components.OverclockPreviewCard
import io.mo.dtbooverclocker.ui.components.TimingCandidateSelector
import io.mo.dtbooverclocker.ui.components.TimingGeometryChart
import io.mo.dtbooverclocker.ui.components.TimingUtils

@Composable
internal fun TimingPanel(
    state: MainUiState,
    onSelect: (String) -> Unit,
    onTarget: (Int) -> Unit,
    onStrategy: (PatchStrategy) -> Unit,
    onPatchMode: (PatchMode) -> Unit,
    onCustomPixelClock: (String) -> Unit,
    onCustomVfp: (String) -> Unit,
    onCustomVbp: (String) -> Unit,
    onCustomHfp: (String) -> Unit,
    onCustomHbp: (String) -> Unit,
    onApplySuggestedCustom: () -> Unit,
    onStageChange: () -> Unit
) {
    val workspace = state.workspace ?: return
    val selected = workspace.candidates.firstOrNull { it.id == state.selectedCandidateId }
        ?: workspace.candidates.first()

    val candidatesInEntry = workspace.candidates.count { it.entryIndex == selected.entryIndex }
    val canDelete = candidatesInEntry > 1
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
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
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        "${workspace.candidates.size} 个候选",
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
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
                activeDtboEntries = state.activeDtboEntries
            )

            HorizontalDivider()

            // 2. 操作模式选择（编辑修改档位 vs 新增独立档位 vs 删除指定档位）
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("操作模式", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    PatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.patchMode == mode,
                            onClick = { onPatchMode(mode) },
                            label = { Text(mode.displayName) },
                            leadingIcon = {
                                Icon(
                                    when (mode) {
                                        PatchMode.APPEND_NEW -> Icons.Default.Add
                                        PatchMode.DELETE_EXISTING -> Icons.Default.Delete
                                        PatchMode.OVERWRITE_EXISTING -> Icons.Default.Build
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (mode == PatchMode.DELETE_EXISTING && state.patchMode == mode)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                    }
                }
                Text(state.patchMode.description, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            if (state.patchMode == PatchMode.DELETE_EXISTING) {
                // 删除档位专属警告与详情卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (canDelete)
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        else
                            MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "准备删除时序档位",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "待删除节点：${TimingUtils.parseTimingNodeName(selected.nodePath)} (${selected.currentHz} Hz)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "完整节点路径：${selected.nodePath}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!canDelete) {
                            Text(
                                "⚠ 严防黑屏限制：当前 DTB 镜像条目仅存此单一档位。屏幕面板必须保留至少 1 个时序档位以供显示驱动初始化，禁止删除！",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                "删除后，当前 DTB 镜像条目仍保留 ${candidatesInEntry - 1} 个时序档位。若此档位为默认 native-mode 开机档位，系统将自动重定向至剩余档位。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 删除执行按钮
                Button(
                    onClick = { showDeleteDialog = true },
                    enabled = canDelete && !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("删除此档位 (暂存)")
                }
            } else if (selected.hasVendorDynamicMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("自动变频档位不支持直接超频", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "该档位包含自动变频或低功耗参数及专用屏幕命令。仅修改刷新率或复制为高刷档位，可能导致黑屏、刷新率切换异常或卡在开机画面。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "请在上方选择同一面板的 normal 普通档位，再编辑或新增。例如新增 144 Hz，应选 normal_120hz，而不是 auto_120_to_30hz。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("请选择普通档位后继续")
                }
            } else {
                // 3. DSI 时序几何剖面图（水平与垂直显像、前肩、同步、后肩比例分布）
                TimingGeometryChart(candidate = selected)

                HorizontalDivider()

                // 4. 目标刷新率调节与快捷预设芯片
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        "目标刷新率：${state.targetHz} Hz",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )

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
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
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

                // 5. 计算策略选择
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("计算策略", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
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

                // 5.1 自定义时序参数配置卡片
                if (state.strategy == PatchStrategy.CUSTOM) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "自定义时序参数",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                TextButton(onClick = onApplySuggestedCustom) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("填入平衡参考值", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            val clockVal = state.customPixelClockText.toLongOrNull()
                            OutlinedTextField(
                                value = state.customPixelClockText,
                                onValueChange = onCustomPixelClock,
                                label = { Text("Pixel Clock / panel-clockrate (Hz)") },
                                placeholder = { Text(selected.pixelClockHz?.toString() ?: "例如 1200000000") },
                                supportingText = {
                                    if (clockVal != null && clockVal > 0) {
                                        Text(TimingUtils.formatClock(clockVal))
                                    } else {
                                        Text("设备树像素/通道时钟，单位 Hz")
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                OutlinedTextField(
                                    value = state.customVfpText,
                                    onValueChange = onCustomVfp,
                                    label = { Text("垂直前肩 (VFP)") },
                                    placeholder = { Text(selected.vFrontPorch?.toString() ?: "行") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = state.customVbpText,
                                    onValueChange = onCustomVbp,
                                    label = { Text("垂直后肩 (VBP)") },
                                    placeholder = { Text(selected.vBackPorch?.toString() ?: "行") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            var showHorizontalCustom by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showHorizontalCustom = !showHorizontalCustom },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "高级消隐参数 (HFP / HBP)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    if (showHorizontalCustom) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }

                            AnimatedVisibility(visible = showHorizontalCustom) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                ) {
                                    OutlinedTextField(
                                        value = state.customHfpText,
                                        onValueChange = onCustomHfp,
                                        label = { Text("水平前肩 (HFP)") },
                                        placeholder = { Text(selected.hFrontPorch?.toString() ?: "px") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = state.customHbpText,
                                        onValueChange = onCustomHbp,
                                        label = { Text("水平后肩 (HBP)") },
                                        placeholder = { Text(selected.hBackPorch?.toString() ?: "px") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            val hAct = selected.hActive ?: 0
                            val hSync = selected.hSync ?: 0
                            val vAct = selected.vActive ?: 0
                            val vSync = selected.vSync ?: 0
                            val vFp = state.customVfpText.toIntOrNull() ?: selected.vFrontPorch ?: 0
                            val vBp = state.customVbpText.toIntOrNull() ?: selected.vBackPorch ?: 0
                            val hFp = state.customHfpText.toIntOrNull() ?: selected.hFrontPorch ?: 0
                            val hBp = state.customHbpText.toIntOrNull() ?: selected.hBackPorch ?: 0
                            val clk = clockVal ?: selected.pixelClockHz ?: 0L

                            val hTotal = hAct + hFp + hSync + hBp
                            val vTotal = vAct + vFp + vSync + vBp
                            if (clk > 0 && hTotal > 0 && vTotal > 0) {
                                val theoreticalHz = clk.toDouble() / (hTotal.toDouble() * vTotal.toDouble())
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                    ) {
                                        Icon(
                                            Icons.Default.Calculate,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "理论推算物理刷新率: ${String.format(Locale.US, "%.2f", theoreticalHz)} Hz (目标: ${state.targetHz} Hz)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. 实时超频推演卡片
                OverclockPreviewCard(
                    candidate = selected,
                    targetHz = state.targetHz,
                    strategy = state.strategy,
                    mode = state.patchMode,
                    customParams = state.customTimingParams
                )

                // 7. 执行修补 / 新增按钮
                Button(onClick = onStageChange, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (state.patchMode == PatchMode.APPEND_NEW) Icons.Default.Add else Icons.Default.Build,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.patchMode == PatchMode.APPEND_NEW) "追加为此面板新档位 (暂存)" else "应用修改到当前时序 (暂存)")
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认删除该时序档位？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("将从工作区设备树中移除 ${TimingUtils.parseTimingNodeName(selected.nodePath)} (${selected.currentHz} Hz) 节点。")
                    Text("删除后将记入待打包修改清单，全部调整完成后可统一打包生成 DTBO 镜像。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onStageChange()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除 (暂存)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
