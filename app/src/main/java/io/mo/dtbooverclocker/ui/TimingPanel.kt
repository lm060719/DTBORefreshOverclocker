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
import io.mo.dtbooverclocker.ui.components.HintText
import io.mo.dtbooverclocker.ui.components.IconLabel
import io.mo.dtbooverclocker.ui.components.NoticeBanner
import io.mo.dtbooverclocker.ui.components.OverclockPreviewCard
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.StatusPill
import io.mo.dtbooverclocker.ui.components.Tone
import io.mo.dtbooverclocker.ui.components.dangerButtonColors
import androidx.compose.foundation.layout.height
import io.mo.dtbooverclocker.ui.components.TimingCandidateSelector
import io.mo.dtbooverclocker.ui.components.TimingGeometryChart
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.ui.i18n.I18n

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
    val strings = I18n.current
    val selected = workspace.candidates.firstOrNull { it.id == state.selectedCandidateId }
        ?: workspace.candidates.first()

    val candidatesInEntry = workspace.candidates.count { it.entryIndex == selected.entryIndex }
    val canDelete = candidatesInEntry > 1
    var showDeleteDialog by remember { mutableStateOf(false) }

    SectionCard(
        title = strings.timingPanelTitle,
        icon = Icons.Default.Tune,
        trailing = { StatusPill(strings.candidatesCount(workspace.candidates.size), tone = Tone.Primary) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {

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
                SubsectionTitle(strings.operationMode)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    PatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.patchMode == mode,
                            onClick = { onPatchMode(mode) },
                            label = { Text(mode.getDisplayName(strings)) },
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
                HintText(state.patchMode.getDescription(strings))
            }

            HorizontalDivider()

            if (state.patchMode == PatchMode.DELETE_EXISTING) {
                // 删除档位专属警告与详情卡片
                SectionCard(title = strings.deleteTimingCandidateTitle, icon = Icons.Default.Warning, tone = Tone.Danger) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            strings.deleteNodeLabel(TimingUtils.parseTimingNodeName(selected.nodePath), selected.currentHz),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            strings.fullNodePath(selected.nodePath),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!canDelete) {
                            NoticeBanner(
                                strings.deleteOnlyModeWarning,
                                tone = Tone.Danger
                            )
                        } else {
                            Text(
                                strings.deleteModeRetainHint(candidatesInEntry - 1),
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
                    colors = dangerButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconLabel(Icons.Default.Delete, strings.deleteThisCandidateBtn)
                }
            } else if (selected.hasVendorDynamicMode) {
                SectionCard(title = strings.autoDynamicModeUnsupported, icon = Icons.Default.Warning, tone = Tone.Danger) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            strings.autoDynamicModeDesc1,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            strings.autoDynamicModeDesc2,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.selectNormalModeToContinue)
                }
            } else {
                // 3. DSI 时序几何剖面图（水平与垂直显像、前肩、同步、后肩比例分布）
                TimingGeometryChart(candidate = selected)

                HorizontalDivider()

                // 4. 目标刷新率调节与快捷预设芯片
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        SubsectionTitle(strings.targetRefreshRate, Modifier.weight(1f))
                        Text(
                            "${state.targetHz}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            " Hz",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Spacing.xs)
                        )
                    }

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
                                strings.quickPresets,
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
                        label = { Text(strings.targetHzInputLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                // 5. 计算策略选择
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SubsectionTitle(strings.calculationStrategy)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PatchStrategy.entries.forEach { strategy ->
                            FilterChip(
                                selected = state.strategy == strategy,
                                onClick = { onStrategy(strategy) },
                                label = { Text(strategy.getDisplayName(strings)) }
                            )
                        }
                    }
                    HintText(state.strategy.getDescription(strings))
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
                                        strings.customParams,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                TextButton(onClick = onApplySuggestedCustom) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(strings.fillSuggestedCustom, style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            val clockVal = state.customPixelClockText.toLongOrNull()
                            OutlinedTextField(
                                value = state.customPixelClockText,
                                onValueChange = onCustomPixelClock,
                                label = { Text(strings.pixelClock) },
                                placeholder = { Text(selected.pixelClockHz?.toString() ?: "例如 1200000000") },
                                supportingText = {
                                    if (clockVal != null && clockVal > 0) {
                                        Text(TimingUtils.formatClock(clockVal))
                                    } else {
                                        Text(strings.pixelClockSupporting)
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
                                    label = { Text(strings.verticalFrontPorch) },
                                    placeholder = { Text(selected.vFrontPorch?.toString() ?: "行") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = state.customVbpText,
                                    onValueChange = onCustomVbp,
                                    label = { Text(strings.verticalBackPorch) },
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
                                    strings.advancedBlankingParams,
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
                                        label = { Text(strings.horizontalFrontPorch) },
                                        placeholder = { Text(selected.hFrontPorch?.toString() ?: "px") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = state.customHbpText,
                                        onValueChange = onCustomHbp,
                                        label = { Text(strings.horizontalBackPorch) },
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
                                            strings.theoreticalRefreshRate(String.format(Locale.US, "%.2f", theoreticalHz), state.targetHz),
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
                Button(onClick = onStageChange, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    IconLabel(
                        if (state.patchMode == PatchMode.APPEND_NEW) Icons.Default.Add else Icons.Default.Build,
                        if (state.patchMode == PatchMode.APPEND_NEW) strings.stageAppendNewMode else strings.stageApplyCurrentMode
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(strings.deleteCandidateConfirmTitle) },
            text = {
                Text(
                    strings.deleteCandidateConfirmBody(
                        TimingUtils.parseTimingNodeName(selected.nodePath),
                        selected.currentHz
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onStageChange()
                    },
                    colors = dangerButtonColors()
                ) {
                    Text(strings.deleteThisCandidateBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
private fun SubsectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}
