package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import io.mo.dtbooverclocker.core.ActivePanelDetector
import io.mo.dtbooverclocker.ui.components.TimingCandidateSelector
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.core.DscPlanner
import io.mo.dtbooverclocker.model.DscParameters
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.DscIssueSeverity
import io.mo.dtbooverclocker.model.DscTopology

@Composable
internal fun DscAnalysisPanel(
    state: MainUiState,
    onSelect: (String) -> Unit,
    onStage: (Int, String, DscParameters) -> Unit
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

    val dscCandidates = remember(workspace.candidates, topologies) {
        val paths = topologies.map { it.entryIndex to it.nodePath }.toSet()
        workspace.candidates.filter { (it.entryIndex to it.nodePath) in paths }
    }
    val selectedCandidate = dscCandidates.firstOrNull { it.id == state.selectedCandidateId }
        ?: state.activePanelIdentifier?.let { ActivePanelDetector.findBestMatchCandidate(dscCandidates, it) }
    val initialTopology = topologies.firstOrNull {
        it.entryIndex == selectedCandidate?.entryIndex && it.nodePath == selectedCandidate.nodePath
    } ?: topologies.firstOrNull {
        state.activePanelIdentifier?.let { identifier ->
            ActivePanelDetector.matchPanel(TimingUtils.parsePanelIdentifier(it.nodePath), identifier)
        } == true
    } ?: topologies.firstOrNull { TimingUtils.isDeviceSpecific(TimingUtils.parsePanelIdentifier(it.nodePath)) }
        ?: topologies.first()
    var selectedKey by rememberSaveable(workspace.rootDir.absolutePath, state.selectedCandidateId, state.activePanelIdentifier) {
        mutableStateOf("${initialTopology.entryIndex}:${initialTopology.nodePath}")
    }
    val topology = topologies.firstOrNull { "${it.entryIndex}:${it.nodePath}" == selectedKey } ?: initialTopology
    val selectedDscCandidate = dscCandidates.firstOrNull {
        it.entryIndex == topology.entryIndex && it.nodePath == topology.nodePath
    }
    val otherTopologies = remember(topologies, dscCandidates) {
        val paths = dscCandidates.map { it.entryIndex to it.nodePath }.toSet()
        topologies.filter { (it.entryIndex to it.nodePath) !in paths }
    }
    val activePanelTopologies = remember(topologies, topology.entryIndex, topology.nodePath) {
        val panel = TimingUtils.parsePanelIdentifier(topology.nodePath)
        topologies.filter {
            it.entryIndex == topology.entryIndex && TimingUtils.parsePanelIdentifier(it.nodePath) == panel
        }
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
                        "DSC 参数编辑",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "参数修改 · 拓扑校验",
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

            if (dscCandidates.isNotEmpty()) {
                TimingCandidateSelector(
                    candidates = dscCandidates,
                    selectedCandidateId = selectedDscCandidate?.id,
                    onSelect = { id ->
                        if (!state.busy) {
                            dscCandidates.firstOrNull { it.id == id }?.let {
                                selectedKey = "${it.entryIndex}:${it.nodePath}"
                                onSelect(id)
                            }
                        }
                    },
                    activePanelIdentifier = state.activePanelIdentifier,
                    activePanelDisplayName = state.activePanelDisplayName,
                    activePanelSource = state.activePanelSource,
                    selectionLabel = "选择要修改 DSC 的时序档位：",
                    selectFallback = false
                )
            }
            if (otherTopologies.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("其他 DSC 节点", style = MaterialTheme.typography.titleSmall)
                Text("以下节点未识别到刷新率档位，可单独选择编辑。", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    otherTopologies.forEach { item ->
                        val selected = item.entryIndex == topology.entryIndex && item.nodePath == topology.nodePath
                        val panel = TimingUtils.parsePanelIdentifier(item.nodePath)
                        val detected = state.activePanelIdentifier?.let { ActivePanelDetector.matchPanel(panel, it) } == true
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                                selectedKey = "${item.entryIndex}:${item.nodePath}"
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(containerColor = if (selected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(TimingUtils.formatPanelDisplayName(panel), fontWeight = FontWeight.SemiBold)
                                Text("DTB[${item.entryIndex}] · ${item.nodePath.substringAfterLast('/')} · ${item.panelWidth ?: "?"} × ${item.panelHeight ?: "?"}", style = MaterialTheme.typography.bodySmall)
                                Text(if (detected) "本机在用" else if (TimingUtils.isDeviceSpecific(panel)) "机型专属" else "公版 / 仿真",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("所选节点 · DSC 参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            key(workspace.rootDir.absolutePath, topology, state.transactions.size) {
                DscParameterEditor(topology, state.busy || state.capabilityScanInProgress, onStage)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("当前节点拓扑", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (topology.hasErrors) "校验状态：存在错误" else "校验状态：拓扑可解析",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (topology.hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
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
                            "所选面板的 ${activePanelTopologies.size} 个 DSC 档位使用同一组核心 DSC 参数。"
                        }
                        else
                        {
                            "所选面板发现 $topologySignatures 组不同 DSC 核心参数，本次只修改所选节点。"
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
                        "修改仅应用于所选节点。暂存后可在概览中撤销或集中打包导出。PPS、RC range 和厂商 DSI command 不会自动同步；DSC 修改仅支持导出验证。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DscParameterEditor(
    topology: DscTopology,
    busy: Boolean,
    onStage: (Int, String, DscParameters) -> Unit
) {
    var version by rememberSaveable { mutableStateOf(topology.version?.let { "0x${it.toString(16)}" }.orEmpty()) }
    var bpc by rememberSaveable { mutableStateOf(topology.bitsPerComponent?.toString().orEmpty()) }
    var bpp by rememberSaveable { mutableStateOf(topology.bitsPerPixel?.toString().orEmpty()) }
    var width by rememberSaveable { mutableStateOf(topology.sliceWidth?.toString().orEmpty()) }
    var height by rememberSaveable { mutableStateOf(topology.sliceHeight?.toString().orEmpty()) }
    var packet by rememberSaveable { mutableStateOf(topology.slicePerPacket?.toString().orEmpty()) }
    var prediction by rememberSaveable { mutableStateOf(topology.blockPredictionEnabled) }

    fun number(text: String, label: String, optional: Boolean = false): Int? {
        val value = text.trim()
        if (optional && value.isEmpty()) return null
        return requireNotNull(if (value.startsWith("0x", true)) value.drop(2).toIntOrNull(16) else value.toIntOrNull()) {
            "$label 请输入有效整数（支持 0x 十六进制）"
        }
    }
    val input = runCatching {
        DscParameters(
            version = number(version, "DSC version", topology.version == null),
            bitsPerComponent = number(bpc, "BPC", topology.bitsPerComponent == null),
            bitsPerPixel = number(bpp, "BPP", topology.bitsPerPixel == null),
            sliceWidth = requireNotNull(number(width, "Slice width")),
            sliceHeight = requireNotNull(number(height, "Slice height")),
            slicePerPacket = requireNotNull(number(packet, "Slice per packet")),
            blockPredictionEnabled = prediction
        ).also { DscPlanner.validate(topology, it) }
    }
    val parameters = input.getOrNull()
    val changed = parameters != null && (
        parameters.version != topology.version || parameters.bitsPerComponent != topology.bitsPerComponent ||
        parameters.bitsPerPixel != topology.bitsPerPixel || parameters.sliceWidth != topology.sliceWidth ||
        parameters.sliceHeight != topology.sliceHeight || parameters.slicePerPacket != topology.slicePerPacket ||
        parameters.blockPredictionEnabled != topology.blockPredictionEnabled)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("直接修改下方数值或开关，再点击“暂存 DSC 修改”保存到工作区。", style = MaterialTheme.typography.bodySmall)
        Text("版本以原始数值填写，例如 0x12 表示 1.2；未定义的版本、BPC/BPP 可留空。", style = MaterialTheme.typography.bodySmall)
        DscNumberField("DSC 版本", version, { version = it }, !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DscNumberField("BPC · 每分量位数", bpc, { bpc = it }, !busy, Modifier.weight(1f))
            DscNumberField("BPP · 每像素位数", bpp, { bpp = it }, !busy, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DscNumberField("Slice width", width, { width = it }, !busy, Modifier.weight(1f))
            DscNumberField("Slice height", height, { height = it }, !busy, Modifier.weight(1f))
        }
        DscNumberField("Slice per packet", packet, { packet = it }, !busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Block Prediction")
            Switch(checked = prediction, onCheckedChange = { prediction = it }, enabled = !busy)
        }
        input.exceptionOrNull()?.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { parameters?.let { onStage(topology.entryIndex, topology.nodePath, it) } },
            enabled = !busy && changed,
            modifier = Modifier.fillMaxWidth()
        ) { Text("暂存 DSC 修改") }
    }
}

@Composable
private fun DscNumberField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier.fillMaxWidth()
    )
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
