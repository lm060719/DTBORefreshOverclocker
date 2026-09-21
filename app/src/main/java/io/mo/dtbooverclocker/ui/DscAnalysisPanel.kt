package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.DscIssueSeverity
import io.mo.dtbooverclocker.model.DscTopology
import io.mo.dtbooverclocker.ui.components.TimingCandidateSelector

@Composable
internal fun DscAnalysisPanel(
    state: MainUiState,
    onSelect: (String) -> Unit
)
{
    val workspace = state.workspace ?: return
    val report = state.capabilityReport
    val topologies = report?.dscTopologies.orEmpty()

    if (state.capabilityScanInProgress && report == null)
    {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "正在解析 DSC 拓扑…",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    if (topologies.isEmpty())
    {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "当前 DTBO 没有发现可建立拓扑的 DSC timing。",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val dscPaths = remember(topologies) { topologies.map { it.entryIndex to it.nodePath }.toSet() }
    val dscCandidates = remember(workspace.candidates, dscPaths) {
        workspace.candidates.filter { candidate ->
            (candidate.entryIndex to candidate.nodePath) in dscPaths
        }
    }
    val selectedCandidate = dscCandidates.firstOrNull { it.id == state.selectedCandidateId }
        ?: dscCandidates.firstOrNull { candidate ->
            state.activePanelIdentifier?.let { candidate.nodePath.contains(it, ignoreCase = true) } == true
        }
        ?: dscCandidates.firstOrNull()
    val topology = selectedCandidate?.let { candidate ->
        topologies.firstOrNull { it.entryIndex == candidate.entryIndex && it.nodePath == candidate.nodePath }
    } ?: topologies.first()

    val activePanelTopologies = remember(topologies, state.activePanelIdentifier) {
        state.activePanelIdentifier?.let { identifier ->
            topologies.filter { it.nodePath.contains(identifier, ignoreCase = true) }
        }.orEmpty().ifEmpty { topologies }
    }
    val topologySignatures = remember(activePanelTopologies) {
        activePanelTopologies.map {
            listOf(
                it.version,
                it.bitsPerComponent,
                it.bitsPerPixel,
                it.sliceWidth,
                it.sliceHeight,
                it.slicePerPacket,
                it.blockPredictionEnabled
            )
        }.distinct().size
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "DSC 拓扑分析",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "只读拓扑分析",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        "${topologies.size} 个 DSC timing",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (dscCandidates.isNotEmpty())
            {
                TimingCandidateSelector(
                    candidates = dscCandidates,
                    selectedCandidateId = selectedCandidate?.id,
                    onSelect = onSelect,
                    activePanelIdentifier = state.activePanelIdentifier,
                    activePanelDisplayName = state.activePanelDisplayName,
                    activePanelSource = state.activePanelSource
                )
            }

            HorizontalDivider()

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                topology.versionDisplay?.let { AssistChip({}, { Text("DSC $it") }) }
                topology.bitsPerComponent?.let { AssistChip({}, { Text("BPC $it") }) }
                topology.bitsPerPixel?.let { AssistChip({}, { Text("BPP $it") }) }
                AssistChip({}, { Text(if (topology.blockPredictionEnabled) "Block Prediction ✓" else "Block Prediction —") })
                AssistChip({}, { Text(if (topology.hasErrors) "存在错误" else "拓扑可解析") })
            }

            TopologyRow(
                label = "面板 / 刷新率",
                value = "${topology.panelWidth ?: "?"} × ${topology.panelHeight ?: "?"} @ ${topology.refreshHz ?: "?"} Hz"
            )
            TopologyRow(
                label = "Slice",
                value = "${topology.sliceWidth ?: "?"} × ${topology.sliceHeight ?: "?"} · 横向 ${topology.horizontalSliceCount ?: "?"} · 纵向 ${topology.verticalSliceCount ?: "?"}"
            )
            TopologyRow(
                label = "Packet / Frame",
                value = "slice-per-pkt ${topology.slicePerPacket ?: "?"} · 每帧 ${topology.slicesPerFrame ?: "?"} slices"
            )
            TopologyRow(
                label = "ROI Alignment",
                value = topology.roiAlignment?.joinToString(prefix = "<", postfix = ">") ?: "未定义"
            )
            TopologyRow(
                label = "节点",
                value = topology.nodePath,
                monospace = true
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (topologySignatures == 1)
                    {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    }
                    else
                    {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        if (topologySignatures == 1) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null
                    )
                    Text(
                        if (topologySignatures == 1)
                        {
                            "当前面板的 ${activePanelTopologies.size} 个 DSC 档位使用同一组核心 DSC 参数。"
                        }
                        else
                        {
                            "当前面板发现 $topologySignatures 组不同 DSC 核心参数，后续开放修改时必须按拓扑分组处理。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("校验结果", fontWeight = FontWeight.SemiBold)
                topology.issues.forEach { issue ->
                    val prefix = when (issue.severity)
                    {
                        DscIssueSeverity.INFO -> "✓"
                        DscIssueSeverity.WARNING -> "⚠"
                        DscIssueSeverity.ERROR -> "✕"
                    }
                    Text(
                        "$prefix ${issue.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (issue.severity == DscIssueSeverity.ERROR)
                        {
                            MaterialTheme.colorScheme.error
                        }
                        else
                        {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Text(
                        "当前 DSC 模块只建立拓扑和一致性检查，不修改 PPS、RC range、厂商 DSI command 或 DSC 参数。后续只有能由拓扑约束验证的字段才会开放写入。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun TopologyRow(
    label: String,
    value: String,
    monospace: Boolean = false
)
{
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}
