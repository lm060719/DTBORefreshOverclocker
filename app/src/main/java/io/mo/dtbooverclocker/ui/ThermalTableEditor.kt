package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.background
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.ChargingAnalyzer
import io.mo.dtbooverclocker.core.ThermalTableAdjuster
import io.mo.dtbooverclocker.model.ChargingField
import java.math.BigDecimal

/** A descending thermal property laid out as levels × channels; [cells] index the editor's field list in cell order. */
internal data class ThermalTable(val name: String, val title: String, val columns: List<String>, val cells: List<Int>) {
    val levels: Int get() = cells.size / columns.size
    fun cell(level: Int, column: Int): Int = cells[level * columns.size + column]

    companion object {
        fun from(fields: List<ChargingField>): List<ThermalTable> =
            fields.indices.filter { fields[it].cellIndex != null && fields[it].parameter.descendingStride > 0 }
                .groupBy { fields[it].parameter.name }
                .mapNotNull { (name, indices) ->
                    val ordered = indices.sortedBy { fields[it].cellIndex }
                    val stride = fields[ordered.first()].parameter.descendingStride
                    // A partial table cannot be shown as a grid; keep it in the plain field list.
                    if (ordered.size % stride != 0 || ordered.map { fields[it].cellIndex } != ordered.indices.toList()) return@mapNotNull null
                    val columns = (0 until stride).map { fields[ordered[it]].column ?: "限流" }
                    ThermalTable(name, fields[ordered.first()].parameter.label.substringBefore(" · "), columns, ordered)
                }
    }
}

@Composable
internal fun ThermalTableEditor(
    table: ThermalTable,
    fields: List<ChargingField>,
    values: List<String>,
    original: List<String>,
    enabled: Boolean,
    onChange: (Map<Int, String>) -> Unit
) {
    val unit = fields[table.cells.first()].parameter.unit
    val linkGroups = remember(table) {
        ThermalTableAdjuster.identicalColumns((0 until table.levels).map { level ->
            table.columns.indices.map { requireNotNull(fields[table.cell(level, it)].value) }
        })
    }
    val canLink = linkGroups.any { it.size > 1 }
    var linked by rememberSaveable(table.name) { mutableStateOf(true) }
    val displayColumns = if (linked && canLink) linkGroups else table.columns.indices.map(::listOf)
    val parsed = table.cells.associateWith { runCatching { ChargingAnalyzer.parseInput(fields[it].parameter, values[it]) }.getOrNull() }
    fun invalid(level: Int, column: Int): Boolean {
        val value = parsed[table.cell(level, column)] ?: return true
        return level > 0 && parsed[table.cell(level - 1, column)]?.let { value > it } == true
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("${table.title} · ${table.levels} 档 × ${table.columns.size} 通道（$unit）",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("第 1 档限流最宽松，档位越高温度越高、限流越严格。左右滑动查看全部通道。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canLink) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("联动相同通道", style = MaterialTheme.typography.labelLarge)
                        Text(
                            linkGroups.filter { it.size > 1 }.joinToString("；") { group -> group.joinToString(" / ") { table.columns[it] } } +
                                " 原值完全相同，合并为一列同时修改",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = linked, onCheckedChange = { linked = it }, enabled = enabled)
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column {
                    HeaderCell("档位", null)
                    repeat(table.levels) { level ->
                        Box(Modifier.size(44.dp, CELL_HEIGHT), contentAlignment = Alignment.Center) {
                            Text("${level + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                displayColumns.forEach { members ->
                    Column {
                        HeaderCell(table.columns[members.first()], if (members.size > 1) "+${members.size - 1} 列联动" else null)
                        repeat(table.levels) { level ->
                            val index = table.cell(level, members.first())
                            TableCell(
                                value = values[index],
                                originalValue = original[index],
                                changed = members.any { values[table.cell(level, it)] != original[table.cell(level, it)] },
                                invalid = members.any { invalid(level, it) },
                                enabled = enabled,
                                description = fields[index].parameter.label
                            ) { text -> onChange(members.associate { table.cell(level, it) to text }) }
                        }
                    }
                }
            }
            if (table.columns.indices.any { column -> (0 until table.levels).any { invalid(it, column) } }) {
                Text("红框：数值无效，或高于上一档。同一通道的后一档不能高于前一档。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            BatchAdjust(table, fields, values, displayColumns, enabled, onChange)
            OutlinedButton(
                onClick = { onChange(table.cells.associateWith { original[it] }) },
                enabled = enabled && table.cells.any { values[it] != original[it] },
                modifier = Modifier.fillMaxWidth()
            ) { Text("恢复本表原值") }
        }
    }
}

@Composable
private fun BatchAdjust(
    table: ThermalTable,
    fields: List<ChargingField>,
    values: List<String>,
    displayColumns: List<List<Int>>,
    enabled: Boolean,
    onChange: (Map<Int, String>) -> Unit
) {
    var expanded by rememberSaveable(table.name) { mutableStateOf(false) }
    var selected by rememberSaveable(table.name, stateSaver = listSaver<List<Int>, Int>(save = { it }, restore = { it })) {
        mutableStateOf(if (table.columns.size == 1) listOf(0) else emptyList())
    }
    var from by rememberSaveable(table.name) { mutableStateOf("1") }
    var to by rememberSaveable(table.name) { mutableStateOf(table.levels.toString()) }
    var percent by rememberSaveable(table.name) { mutableStateOf("10") }
    var message by remember(table) { mutableStateOf<String?>(null) }

    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起批量调整" else "批量调整（按百分比）") }
    if (!expanded) return
    if (table.columns.size > 1) {
        Text("选择通道", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            displayColumns.forEach { members ->
                val on = members.all { it in selected }
                FilterChip(
                    selected = on, enabled = enabled,
                    onClick = { selected = if (on) selected - members.toSet() else (selected + members).distinct() },
                    label = { Text(table.columns[members.first()] + if (members.size > 1) " 等 ${members.size} 列" else "") }
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SmallNumberField(from, "从第几档", enabled, Modifier.weight(1f)) { from = it }
        SmallNumberField(to, "到第几档", enabled, Modifier.weight(1f)) { to = it }
        // Number keyboards on some IMEs lack a minus sign.
        SmallNumberField(percent, "调整 %", enabled, Modifier.weight(1f), KeyboardType.Text) { percent = it }
    }
    Text("例：+10 表示上调 10%，-20 表示下调 20%。结果按 10 ${fields[table.cells.first()].parameter.unit} 取整；" +
        "超出相邻档位时自动截断，保证后一档不高于前一档。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(
        onClick = {
            message = runCatching {
                require(selected.isNotEmpty()) { "请先选择通道" }
                val first = from.trim().toIntOrNull()
                val last = to.trim().toIntOrNull()
                require(first != null && last != null && first in 1..last && last <= table.levels) { "档位范围应在 1～${table.levels} 之间" }
                val ratio = percent.trim().removePrefix("+").toBigDecimalOrNull() ?: throw IllegalArgumentException("请输入有效的百分比")
                val current = (0 until table.levels).map { level ->
                    table.columns.indices.map { column ->
                        val index = table.cell(level, column)
                        runCatching { ChargingAnalyzer.parseInput(fields[index].parameter, values[index]) }
                            .getOrElse { throw IllegalArgumentException("第 ${level + 1} 档 ${table.columns[column]} 数值无效，请先修正") }
                    }
                }
                val scale = fields[table.cells.first()].parameter.scale
                val result = ThermalTableAdjuster.scale(current, selected.toSet(), first - 1, last - 1, ratio, 10 * scale)
                val updates = buildMap {
                    for (level in 0 until table.levels) for (column in table.columns.indices) {
                        val value = result.levels[level][column]
                        if (value != current[level][column]) {
                            val index = table.cell(level, column)
                            put(index, ChargingAnalyzer.displayValue(fields[index].copy(value = value)))
                        }
                    }
                }
                onChange(updates)
                "已调整 ${updates.size} 个单元格" + if (result.clamped > 0) "，其中 ${result.clamped} 个受相邻档位限制被截断" else ""
            }.getOrElse { it.message ?: "批量调整失败" }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text("应用到表格") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}

private val CELL_HEIGHT = 54.dp

@Composable
private fun HeaderCell(title: String, subtitle: String?) {
    Column(Modifier.size(92.dp, 44.dp).padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
    }
}

@Composable
private fun TableCell(
    value: String,
    originalValue: String,
    changed: Boolean,
    invalid: Boolean,
    enabled: Boolean,
    description: String,
    onValueChange: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier.size(92.dp, CELL_HEIGHT).padding(Spacing.xxs)
            .background(if (changed) colors.primaryContainer else colors.surface, shape)
            .border(if (invalid) 2.dp else 1.dp, if (invalid) colors.error else if (changed) colors.primary else colors.outlineVariant, shape)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (changed) colors.onPrimaryContainer else colors.onSurface,
                    textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
            )
            if (changed) Text("原 $originalValue", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SmallNumberField(
    value: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true, modifier = modifier,
        label = { Text(label, maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}
