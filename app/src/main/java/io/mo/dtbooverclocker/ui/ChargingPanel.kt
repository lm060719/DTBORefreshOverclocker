package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.ChargingAnalyzer
import io.mo.dtbooverclocker.core.ChargingPlanner
import io.mo.dtbooverclocker.model.ChargingNode
import io.mo.dtbooverclocker.model.FeatureModuleKind

@Composable
internal fun ChargingPanel(state: MainUiState, onStage: (ChargingNode, Map<String, String>) -> Unit) {
    val workspace = state.workspace ?: return
    val nodes = state.capabilityReport?.chargingNodes.orEmpty()
    val uniquePathCount = remember(nodes) { nodes.map { it.nodePath }.distinct().size }
    val editableNodes = remember(nodes) { nodes.filter { it.editableCount > 0 } }
    val editableNodeCount = editableNodes.size
    val readOnlyNodeCount = nodes.size - editableNodeCount
    val editableParameterCount = remember(nodes) { nodes.sumOf { it.editableCount } }
    val enabled = !state.busy && !state.capabilityScanInProgress
    if (nodes.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Text(if (state.capabilityScanInProgress) "正在扫描充电参数…"
                else "当前 DTBO 未发现充电参数。相关配置可能位于基础 DTB、vendor_boot 或电源管理驱动中。",
                Modifier.padding(16.dp))
        }
        return
    }
    var showReadOnlyNodes by rememberSaveable(workspace.rootDir.absolutePath) { mutableStateOf(false) }
    val visibleNodes = remember(nodes, showReadOnlyNodes) {
        if (showReadOnlyNodes) nodes else editableNodes
    }
    var selectedKey by rememberSaveable(workspace.rootDir.absolutePath) {
        mutableStateOf(editableNodes.firstOrNull()?.key ?: nodes.first().key)
    }
    val node = visibleNodes.firstOrNull { it.key == selectedKey }
        ?: visibleNodes.firstOrNull()
        ?: nodes.first()
    LaunchedEffect(node.key, showReadOnlyNodes) {
        if (selectedKey != node.key) selectedKey = node.key
    }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val thermalNode = remember(editableNodes) { editableNodes.firstOrNull(::hasThermalTable) }
    val drafts = rememberSaveableStateHolder()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Charging 参数编辑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "${editableParameterCount} 个可编辑参数 · ${editableNodeCount} 个可编辑节点 · " +
                        "${uniquePathCount} 个唯一路径 / ${nodes.size} 个 DTB 实例",
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (readOnlyNodeCount > 0) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("显示只读节点", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "默认隐藏 $readOnlyNodeCount 个没有已验证编辑项的相关节点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showReadOnlyNodes,
                        onCheckedChange = {
                            showReadOnlyNodes = it
                            selecting = false
                        }
                    )
                }
            }

            Text("选择充电节点", style = MaterialTheme.typography.labelLarge)
            OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("DTB ${node.entryIndex} · ${node.nodePath.substringAfterLast('/').ifBlank { "/" }}", fontWeight = FontWeight.Medium)
                    Text(node.nodePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    node.compatible?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if ("oplus," in it)
                        {
                            Text(
                                "已启用 OPlus 保守绑定：仅开放已验证的单值 mA / mV 参数，复杂策略表保持只读。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    node.targetLabel?.let { Text("Overlay 目标：&$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text("${node.editableCount} 个可编辑参数 · status: ${node.status ?: "未声明"}", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (thermalNode != null && thermalNode.key != node.key) {
                FilledTonalButton(onClick = { selectedKey = thermalNode.key; selecting = false }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("转到温控表：DTB ${thermalNode.entryIndex} · ${thermalNode.nodePath.substringAfterLast('/')}")
                }
            }
            if (visibleNodes.size > 1) {
                OutlinedButton(onClick = { selecting = !selecting }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selecting) "收起节点列表" else "切换充电节点（${visibleNodes.size}）")
                }
                if (selecting) visibleNodes.forEach { candidate ->
                    OutlinedCard(
                        onClick = { selectedKey = candidate.key; selecting = false }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, if (candidate.key == node.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (candidate.key == node.key) Icon(Icons.Default.CheckCircle, "当前节点", tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("DTB ${candidate.entryIndex} · ${candidate.editableCount} 个可编辑参数" +
                                    if (hasThermalTable(candidate)) " · 含温控表" else "", style = MaterialTheme.typography.labelLarge)
                                Text(candidate.nodePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            if (node.status != null && node.status !in listOf("okay", "ok")) {
                Text("此节点 status 为 ${node.status}，修改参数不会自动启用节点。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (node.editableCount == 0 && editableNodes.isNotEmpty()) {
                Text("这是只读相关节点，没有经过验证的可编辑参数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { selectedKey = editableNodes.first().key }, enabled = enabled) {
                    Text("返回可编辑节点")
                }
            }
            HorizontalDivider()
            // Include the source snapshot so stage/undo/import cannot restore a stale form.
            drafts.SaveableStateProvider("${workspace.rootDir.absolutePath}:${node.key}") {
                key(node) { ChargingEditor(node, enabled, onStage) }
            }
            val staged = state.moduleStagedChanges.filter {
                it.module == FeatureModuleKind.CHARGING && it.entryIndex == node.entryIndex && node.nodePath in it.affectedNodePaths
            }
            if (staged.isNotEmpty()) {
                Text("此节点已暂存 ${staged.size} 次修改。到“概览”集中打包，或撤销最近事务。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun hasThermalTable(node: ChargingNode) =
    node.fields.any { it.issue == null && it.cellIndex != null && it.parameter.descendingStride > 0 }

@Composable
private fun ChargingEditor(node: ChargingNode, enabled: Boolean, onStage: (ChargingNode, Map<String, String>) -> Unit) {
    val fields = node.fields.filter { it.issue == null }
    val original = remember(node) { fields.map(ChargingAnalyzer::displayValue) }
    var values by rememberSaveable(node, stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })) {
        mutableStateOf(original)
    }
    var showOther by rememberSaveable { mutableStateOf(false) }
    var showInvalid by rememberSaveable { mutableStateOf(false) }
    val inputs = fields.mapIndexed { index, field -> field.inputKey to values[index] }.toMap()
    val result = remember(node, values) { runCatching { ChargingPlanner.preview(node, inputs) } }
    val preview = result.getOrNull()
    val dirty = values != original
    val tables = remember(node) { ThermalTable.from(fields) }
    val tableCells = remember(tables) { tables.flatMap { it.cells }.toSet() }
    val plainIndices = remember(node, tableCells) { fields.indices.filter { it !in tableCells } }
    val groups = remember(node) { plainIndices.map { fields[it].group }.distinct() }
    var selectedGroup by rememberSaveable(node) { mutableStateOf(groups.firstOrNull().orEmpty()) }
    var page by rememberSaveable(node, selectedGroup) { mutableIntStateOf(0) }
    val groupIndices = plainIndices.filter { fields[it].group == selectedGroup }
    val pageCount = ((groupIndices.size + 15) / 16).coerceAtLeast(1)

    if (fields.isEmpty()) {
        Text("已发现相关节点，但没有可确认单位与格式的编辑参数。可在下方查看原始属性。", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("充电参数", style = MaterialTheme.typography.labelLarge)
        Text("按参数标注的单位编辑，自动换算为设备树单位；只修改当前 DTB 的当前节点。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tables.isNotEmpty()) {
            Text("温控表中的数值是各温控档位的限流值，保持原有档位数量；同一通道的后一档不能高于前一档。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tables.forEach { table ->
            ThermalTableEditor(table, fields, values, original, enabled) { updates ->
                values = values.mapIndexed { position, previous -> updates[position] ?: previous }
            }
        }
        if (tables.isNotEmpty() && groups.isNotEmpty()) Text("其他参数", style = MaterialTheme.typography.labelLarge)
        if (groups.size > 1) {
            Text("参数分组（可左右滑动）", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    val changedCount = plainIndices.count { fields[it].group == group && values[it] != original[it] }
                    FilterChip(selected = selectedGroup == group, onClick = { selectedGroup = group; page = 0 },
                        label = { Text(group + if (changedCount > 0) " · $changedCount 项待暂存" else "") })
                }
            }
        }
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                Text("${page + 1} / $pageCount 页 · ${groupIndices.size} 项")
                TextButton(onClick = { page++ }, enabled = page + 1 < pageCount) { Text("下一页") }
            }
        }
        groupIndices.drop(page * 16).take(16).forEach { index ->
            val field = fields[index]
            val parameter = field.parameter
            val error = if (values[index] == original[index]) null else runCatching {
                ChargingAnalyzer.parseInput(parameter, values[index])
            }.exceptionOrNull()?.message

            if (parameter.boolean) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(parameter.label, style = MaterialTheme.typography.bodyMedium)
                        Text(parameter.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            if (field.exists) "当前：开启" else "当前：关闭（属性未声明）",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = values[index] == "true",
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            values = values.mapIndexed { position, previous ->
                                if (position == index) checked.toString() else previous
                            }
                        }
                    )
                }
            } else {
                OutlinedTextField(
                    value = values[index],
                    onValueChange = { value ->
                        values = values.mapIndexed { position, previous ->
                            if (position == index) value else previous
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("${parameter.label}（${parameter.unit}）") },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        Column {
                            Text(error ?: "当前：${original[index]} ${parameter.unit} · 原始：${field.value} ${parameter.rawUnit}")
                            Text(field.inputKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }
    }
    val invalidFields = node.fields.filter { it.issue != null }
    if (invalidFields.isNotEmpty()) {
        TextButton(onClick = { showInvalid = !showInvalid }) {
            Text(if (showInvalid) "收起只读 / 异常参数" else "查看只读 / 异常参数（${invalidFields.size}）")
        }
        if (showInvalid) {
            invalidFields.forEach { field ->
                Text(
                    "${field.parameter.name}\n${field.rawValue ?: "<空属性>"}\n${field.issue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (node.otherProperties.isNotEmpty()) {
        TextButton(onClick = { showOther = !showOther }) { Text(if (showOther) "收起其他属性" else "查看其他原始属性（${node.otherProperties.size}）") }
        if (showOther) {
            Text("以下属性未纳入充电参数编辑，按原样保留。", style = MaterialTheme.typography.bodySmall)
            node.otherProperties.forEach { (name, raw) ->
                Text("$name = ${raw ?: "<空属性>"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (fields.isNotEmpty()) {
        HorizontalDivider()
        Text("修改预览", style = MaterialTheme.typography.labelLarge)
        when {
            result.isFailure && dirty -> Text(result.exceptionOrNull()?.message ?: "参数无效", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            preview?.changes?.isNotEmpty() == true -> preview.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            else -> Text("尚未修改参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
                Text(
                    "请按电池和充电芯片规格设置电流、电压。参数合法不代表硬件支持；修改仅允许导出验证，暂存后到概览打包。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        OutlinedButton(onClick = { values = original }, enabled = enabled && dirty, modifier = Modifier.fillMaxWidth()) {
            Text("重置未暂存输入")
        }
        Button(onClick = { onStage(node, inputs) }, enabled = enabled && preview?.values?.isNotEmpty() == true,
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.BatteryChargingFull, null)
            Spacer(Modifier.width(8.dp))
            Text("暂存充电修改")
        }
    }
}
