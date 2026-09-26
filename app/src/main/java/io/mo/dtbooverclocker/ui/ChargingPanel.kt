package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.ui.components.HintText
import io.mo.dtbooverclocker.ui.components.IconLabel
import io.mo.dtbooverclocker.ui.components.NoticeBanner
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.Tone
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.ChargingAnalyzer
import io.mo.dtbooverclocker.core.ChargingPlanner
import io.mo.dtbooverclocker.model.ChargingNode
import io.mo.dtbooverclocker.model.FeatureModuleKind
import io.mo.dtbooverclocker.ui.i18n.I18n

@Composable
internal fun ChargingPanel(state: MainUiState, onStage: (ChargingNode, Map<String, String>) -> Unit) {
    val strings = I18n.current
    val workspace = state.workspace ?: return
    val nodes = state.capabilityReport?.chargingNodes.orEmpty()
    val uniquePathCount = remember(nodes) { nodes.map { it.nodePath }.distinct().size }
    val editableNodes = remember(nodes) { nodes.filter { it.editableCount > 0 } }
    val editableNodeCount = editableNodes.size
    val readOnlyNodeCount = nodes.size - editableNodeCount
    val editableParameterCount = remember(nodes) { nodes.sumOf { it.editableCount } }
    val enabled = !state.busy && !state.capabilityScanInProgress
    if (nodes.isEmpty()) {
        SectionCard(title = strings.chargingTitle, icon = Icons.Default.BatteryChargingFull) {
            HintText(if (state.capabilityScanInProgress) strings.scanningCharging
                else strings.noChargingNodes)
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

    SectionCard(title = strings.chargingTitle, icon = Icons.Default.BatteryChargingFull) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    strings.chargingParamsSummary(editableParameterCount, editableNodeCount, uniquePathCount, nodes.size),
                    Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
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
                        Text(strings.showReadOnlyNodes, style = MaterialTheme.typography.labelLarge)
                        Text(
                            strings.hideReadOnlyDesc(readOnlyNodeCount),
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

            SubTitle(strings.selectChargingNode)
            OutlinedCard(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("DTB ${node.entryIndex} · ${node.nodePath.substringAfterLast('/').ifBlank { "/" }}", style = MaterialTheme.typography.titleSmall)
                    Text(node.nodePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    node.compatible?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if ("oplus," in it)
                        {
                            Text(
                                strings.oplusConservativeNotice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    node.targetLabel?.let { Text(strings.overlayTarget(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text(strings.editableParamsAndStatus(node.editableCount, node.status ?: "未声明"), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (thermalNode != null && thermalNode.key != node.key) {
                FilledTonalButton(onClick = { selectedKey = thermalNode.key; selecting = false }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(strings.goToThermalTable(thermalNode.entryIndex, thermalNode.nodePath.substringAfterLast('/')))
                }
            }
            if (visibleNodes.size > 1) {
                OutlinedButton(onClick = { selecting = !selecting }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.toggleNodeList(selecting, visibleNodes.size))
                }
                if (selecting) visibleNodes.forEach { candidate ->
                    OutlinedCard(
                        onClick = { selectedKey = candidate.key; selecting = false }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (candidate.key == node.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (candidate.key == node.key) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("DTB ${candidate.entryIndex} · ${strings.editableParamsAndStatus(candidate.editableCount, "").replace(" · status: ", "")}" +
                                    if (hasThermalTable(candidate)) strings.nodeWithThermal else "", style = MaterialTheme.typography.labelLarge)
                                Text(candidate.nodePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            if (node.status != null && node.status !in listOf("okay", "ok")) {
                NoticeBanner(strings.nodeStatusWarning(node.status), tone = Tone.Warning)
            }
            if (node.editableCount == 0 && editableNodes.isNotEmpty()) {
                NoticeBanner(strings.readOnlyNodeNotice, tone = Tone.Neutral)
                OutlinedButton(onClick = { selectedKey = editableNodes.first().key }, enabled = enabled) {
                    Text(strings.backToEditableNode)
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
                NoticeBanner(strings.stagedModificationsNotice(staged.size), tone = Tone.Success)
            }
        }
    }
}

private fun hasThermalTable(node: ChargingNode) =
    node.fields.any { it.issue == null && it.cellIndex != null && it.parameter.descendingStride > 0 }

@Composable
private fun ChargingEditor(node: ChargingNode, enabled: Boolean, onStage: (ChargingNode, Map<String, String>) -> Unit) {
    val strings = I18n.current
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
        Text(strings.noUnitParametersFound, style = MaterialTheme.typography.bodyMedium)
    } else {
        SubTitle(strings.chargingParametersTitle)
        Text(strings.chargingParametersHint,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tables.isNotEmpty()) {
            Text(strings.thermalTableRulesHint,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tables.forEach { table ->
            ThermalTableEditor(table, fields, values, original, enabled) { updates ->
                values = values.mapIndexed { position, previous -> updates[position] ?: previous }
            }
        }
        if (tables.isNotEmpty() && groups.isNotEmpty()) SubTitle(strings.otherParameters)
        if (groups.size > 1) {
            Text(strings.parameterGroups, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                groups.forEach { group ->
                    val changedCount = plainIndices.count { fields[it].group == group && values[it] != original[it] }
                    FilterChip(selected = selectedGroup == group, onClick = { selectedGroup = group; page = 0 },
                        label = { Text(group + if (changedCount > 0) strings.pendingStageCount(changedCount) else "") })
                }
            }
        }
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text(strings.previousPage) }
                Text(strings.pageAndItemsCount(page + 1, pageCount, groupIndices.size))
                TextButton(onClick = { page++ }, enabled = page + 1 < pageCount) { Text(strings.nextPage) }
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(parameter.label, style = MaterialTheme.typography.bodyMedium)
                        Text(parameter.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            if (field.exists) strings.statusEnabled else strings.statusDisabled,
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
                            Text(error ?: strings.currentValueWithRaw(original[index], parameter.unit, field.value.toString(), parameter.rawUnit))
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
            Text(strings.toggleReadOnlyFields(showInvalid, invalidFields.size))
        }
        if (showInvalid) {
            invalidFields.forEach { field ->
                Text(
                    "${field.parameter.name}\n${field.rawValue ?: "<${strings.statusDisabled.substringAfter("：")}>"}\n${field.issue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (node.otherProperties.isNotEmpty()) {
        TextButton(onClick = { showOther = !showOther }) { Text(strings.toggleOtherProps(showOther, node.otherProperties.size)) }
        if (showOther) {
            Text(strings.otherPropsPreserved, style = MaterialTheme.typography.bodySmall)
            node.otherProperties.forEach { (name, raw) ->
                Text("$name = ${raw ?: "<empty>"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (fields.isNotEmpty()) {
        HorizontalDivider()
        SubTitle(strings.modificationPreview)
        when {
            result.isFailure && dirty -> NoticeBanner(result.exceptionOrNull()?.message ?: "参数无效", tone = Tone.Danger)
            preview?.changes?.isNotEmpty() == true -> preview.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            else -> Text(strings.noModificationsYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NoticeBanner(
            strings.batterySpecsWarning,
            tone = Tone.Warning
        )
        OutlinedButton(onClick = { values = original }, enabled = enabled && dirty, modifier = Modifier.fillMaxWidth()) {
            Text(strings.resetUnstagedInput)
        }
        Button(onClick = { onStage(node, inputs) }, enabled = enabled && preview?.values?.isNotEmpty() == true,
            modifier = Modifier.fillMaxWidth()) {
            IconLabel(Icons.Default.BatteryChargingFull, strings.stageChargingChanges)
        }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}
