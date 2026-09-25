package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.ResolutionScope
import io.mo.dtbooverclocker.ui.components.TimingCandidateSelector

@Composable
internal fun ResolutionPanel(
    state: MainUiState,
    onSelect: (String) -> Unit,
    onWidth: (String) -> Unit,
    onHeight: (String) -> Unit,
    onScope: (ResolutionScope) -> Unit,
    onPreset: (Int, Int) -> Unit,
    onStage: () -> Unit
)
{
    val workspace = state.workspace ?: return
    val candidates = remember(workspace.candidates) {
        workspace.candidates.filter { candidate ->
            candidate.hActive != null && candidate.vActive != null
        }
    }
    if (candidates.isEmpty())
    {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "当前工作区没有识别到包含 width / height 的时序节点。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val selected = candidates.firstOrNull { it.id == state.selectedCandidateId }
        ?: candidates.first()
    val sourceWidth = requireNotNull(selected.hActive)
    val sourceHeight = requireNotNull(selected.vActive)
    val parentPath = selected.nodePath.substringBeforeLast('/')
    val matchingCount = workspace.candidates.count { candidate ->
        candidate.entryIndex == selected.entryIndex &&
            candidate.nodePath.substringBeforeLast('/') == parentPath &&
            candidate.hActive == sourceWidth &&
            candidate.vActive == sourceHeight
    }
    val targetWidth = state.resolutionWidthText.toIntOrNull()
    val targetHeight = state.resolutionHeightText.toIntOrNull()
    val aspectValid = targetWidth != null && targetHeight != null &&
        sourceWidth.toLong() * targetHeight.toLong() ==
        targetWidth.toLong() * sourceHeight.toLong()
    val isDownscale = targetWidth != null && targetHeight != null &&
        targetWidth in 320..sourceWidth &&
        targetHeight in 480..sourceHeight &&
        (targetWidth != sourceWidth || targetHeight != sourceHeight)

    val presets = remember(sourceWidth, sourceHeight) {
        buildList {
            if (sourceWidth % 4 == 0 && sourceHeight % 4 == 0)
            {
                add(sourceWidth * 3 / 4 to sourceHeight * 3 / 4)
            }
            if (sourceWidth % 2 == 0 && sourceHeight % 2 == 0)
            {
                add(sourceWidth / 2 to sourceHeight / 2)
            }
        }.distinct()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "分辨率规划",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "保守模式 · 仅导出验证",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            TimingCandidateSelector(
                candidates = candidates,
                selectedCandidateId = selected.id,
                onSelect = onSelect,
                activePanelIdentifier = state.activePanelIdentifier,
                activePanelDisplayName = state.activePanelDisplayName,
                activePanelSource = state.activePanelSource,
                activeDtboEntries = state.activeDtboEntries
            )

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前分辨率", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${sourceWidth} × ${sourceHeight} · 同组可同步 $matchingCount 个档位",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "规划器会在每个目标 timing 上检查 panel width/height、DSC slice 拓扑和已识别的 ROI 对齐关系。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (presets.isNotEmpty())
            {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "等比例预设:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    presets.forEach { (width, height) ->
                        AssistChip(
                            onClick = { onPreset(width, height) },
                            label = { Text("$width × $height") }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.resolutionWidthText,
                    onValueChange = onWidth,
                    label = { Text("目标宽度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.resolutionHeightText,
                    onValueChange = onHeight,
                    label = { Text("目标高度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (targetWidth != null && targetHeight != null && !aspectValid)
            {
                Text(
                    "目标宽高比与原始 ${sourceWidth}×${sourceHeight} 不一致。为避免未知裁切/扫描行为，当前规划器会拒绝暂存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("修改范围", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ResolutionScope.entries.forEach { scope ->
                        FilterChip(
                            selected = state.resolutionScope == scope,
                            onClick = { onScope(scope) },
                            label = { Text(scope.displayName) }
                        )
                    }
                }
                Text(
                    state.resolutionScope.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        "DSC 节点会保持原横向 slice 数量并重新计算 slice-width；只有 ROI 恰好符合已验证的“全宽重复模式”时才会同步改写，否则直接拒绝，不猜参数。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "本版本不会自动改 porch、PHY、PPS 或厂商命令序列，也不会直接 Root 刷写分辨率修改。请先导出镜像/刷机包并离线验证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Button(
                onClick = onStage,
                enabled = !state.busy && aspectValid && isDownscale,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AspectRatio, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("暂存分辨率修改（仅导出验证）")
            }
        }
    }
}
