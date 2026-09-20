package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDiff
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private data class DocumentLoadResult(
    val document: DeviceTreeDocument? = null,
    val loading: Boolean = true,
    val error: String? = null
)

@Composable
fun DeviceTreeScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onSetProperty: (Int, String, String, String?) -> Unit,
    onAddProperty: (Int, String, String, String?) -> Unit,
    onDeleteProperty: (Int, String, String) -> Unit,
    onUndoChange: (String) -> Unit
) {
    val workspace = state.workspace
    var query by rememberSaveable(workspace?.rootDir?.path) { mutableStateOf("") }
    var selectedEntry by rememberSaveable(workspace?.rootDir?.path) { mutableIntStateOf(0) }
    var selectedNodePath by rememberSaveable(workspace?.rootDir?.path) { mutableStateOf<String?>(null) }
    var editorProperty by remember { mutableStateOf<DeviceTreeProperty?>(null) }
    var addingProperty by remember { mutableStateOf(false) }
    var deleteProperty by remember { mutableStateOf<DeviceTreeProperty?>(null) }

    val entry = selectedEntry.coerceIn(
        0,
        (workspace?.metadata?.entries?.lastIndex ?: 0).coerceAtLeast(0)
    )
    val file = workspace?.let { File(it.rootDir, "dts/entry_$entry.dts") }?.takeIf { it.isFile }
    val invalidation = state.stagedChanges.size to state.deviceTreeChanges.size

    val loaded by produceState(
        initialValue = DocumentLoadResult(),
        file,
        invalidation
    ) {
        value = try {
            val text = withContext(Dispatchers.IO) { file?.readText() }
            val document = withContext(Dispatchers.Default) {
                if (text == null) null else DeviceTreeParser.parse(entry, text) { ensureActive() }
            }
            DocumentLoadResult(document = document, loading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DocumentLoadResult(loading = false, error = error.message ?: "读取失败")
        }
    }

    val document = loaded.document
    LaunchedEffect(document?.entryIndex) {
        selectedNodePath = document?.root?.path
    }

    val visibleNodes = remember(document, query) {
        val nodes = document?.flatten().orEmpty()
        val search = query.trim()
        if (search.isBlank()) {
            nodes
        } else {
            nodes.filter { node ->
                node.path.contains(search, ignoreCase = true) ||
                    node.name.contains(search, ignoreCase = true) ||
                    node.label?.contains(search, ignoreCase = true) == true ||
                    node.properties.any { property ->
                        property.name.contains(search, ignoreCase = true) ||
                            property.rawValue?.contains(search, ignoreCase = true) == true ||
                            property.displayValue.contains(search, ignoreCase = true)
                    }
            }
        }
    }
    val selectedNode = document?.findNode(selectedNodePath ?: "/")
    val changesForEntry = state.deviceTreeChanges.filter { it.entryIndex == entry }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设备树", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "结构化浏览、搜索与通用属性编辑。自由编辑默认禁止 Root 直刷，先导出验证。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (workspace == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("请先在“概览”加载一个 DTBO 工作区。", modifier = Modifier.padding(18.dp))
                }
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(workspace.metadata.entries.size, key = { it }) { index ->
                        val available = File(workspace.rootDir, "dts/entry_$index.dts").isFile
                        FilterChip(
                            selected = entry == index,
                            onClick = {
                                selectedEntry = index
                                selectedNodePath = null
                            },
                            enabled = available,
                            label = { Text("Entry $index") }
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text("搜索节点 / 属性 / 值") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                when {
                    loaded.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    loaded.error != null -> Text(
                        "设备树读取失败：${loaded.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                    document == null -> Text("该 Entry 无法反编译为可编辑 DTS。")
                    else -> Text(
                        "Entry $entry · ${document.flatten().size} 个节点 · 当前显示 ${visibleNodes.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (document != null) {
                item {
                    Text("节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(visibleNodes, key = { it.path }) { node ->
                    val depth = if (query.isNotBlank()) 0 else depthOf(node.path)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (depth * 10).dp)
                            .clickable { selectedNodePath = node.path },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedNodePath == node.path) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        )
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                node.name,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                node.path,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "${node.propertyCount} 个属性 · ${node.childCount} 个直接子节点",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                selectedNode?.let { node ->
                    item { HorizontalDivider() }
                    item {
                        NodeDetailCard(
                            node = node,
                            onEdit = { editorProperty = it },
                            onDelete = { deleteProperty = it },
                            onAdd = { addingProperty = true }
                        )
                    }
                }

                if (changesForEntry.isNotEmpty()) {
                    item { HorizontalDivider() }
                    item {
                        Text(
                            "暂存 Diff · ${changesForEntry.size} 项",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(changesForEntry, key = { it.id }) { change ->
                        ChangeCard(
                            change = change,
                            undoEnabled = state.deviceTreeChanges.lastOrNull()?.id == change.id,
                            onUndo = { onUndoChange(change.id) }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (selectedNode != null && (addingProperty || editorProperty != null)) {
        PropertyEditorDialog(
            node = selectedNode,
            property = editorProperty,
            adding = addingProperty,
            onDismiss = {
                addingProperty = false
                editorProperty = null
            },
            onConfirm = { name, rawValue ->
                if (addingProperty) {
                    onAddProperty(entry, selectedNode.path, name, rawValue)
                } else {
                    onSetProperty(entry, selectedNode.path, name, rawValue)
                }
                addingProperty = false
                editorProperty = null
            }
        )
    }

    if (selectedNode != null && deleteProperty != null) {
        val property = deleteProperty!!
        AlertDialog(
            onDismissRequest = { deleteProperty = null },
            title = { Text("删除属性") },
            text = {
                Text(
                    "${selectedNode.path}/${property.name}\n\n该修改会进入暂存区，可在没有后续修改时撤销。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProperty(entry, selectedNode.path, property.name)
                        deleteProperty = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProperty = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun NodeDetailCard(
    node: DeviceTreeNode,
    onEdit: (DeviceTreeProperty) -> Unit,
    onDelete: (DeviceTreeProperty) -> Unit,
    onAdd: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("节点详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(node.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            node.label?.let { Text("label: $it", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("新增属性")
            }

            if (node.properties.isEmpty()) {
                Text("该节点没有直接属性。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                node.properties.forEach { property ->
                    HorizontalDivider()
                    Row {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                property.name,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${property.type} · ${property.displayValue}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                property.rawStatement,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { onEdit(property) }) {
                            Icon(Icons.Default.Edit, "编辑属性")
                        }
                        IconButton(onClick = { onDelete(property) }) {
                            Icon(Icons.Default.DeleteOutline, "删除属性")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyEditorDialog(
    node: DeviceTreeNode,
    property: DeviceTreeProperty?,
    adding: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var name by remember(property, adding) { mutableStateOf(property?.name.orEmpty()) }
    var rawValue by remember(property, adding) { mutableStateOf(property?.rawValue.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "新增属性" else "编辑属性") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    node.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = adding,
                    label = { Text("属性名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rawValue,
                    onValueChange = { rawValue = it },
                    label = { Text("Raw DTS 值") },
                    supportingText = {
                        Text("例如 <0x78>、\"qcom,panel\"、[01 ff]；留空表示 boolean 属性。")
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), rawValue.trim().ifEmpty { null }) },
                enabled = name.isNotBlank()
            ) { Text("暂存修改") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ChangeCard(
    change: DeviceTreeChange,
    undoEnabled: Boolean,
    onUndo: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                DeviceTreeDiff.render(change),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                Icon(Icons.Default.History, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (undoEnabled) "可撤销最近一项修改" else "后续已有修改，当前项不可单独撤销",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onUndo, enabled = undoEnabled) { Text("撤销") }
            }
        }
    }
}

private fun depthOf(path: String): Int {
    if (path == "/") return 0
    return path.trim('/').split('/').size
}

internal data class DtsNodeSummary(
    val path: String,
    val propertyCount: Int
)

internal fun parseNodeSummaries(
    text: String,
    checkCancellation: () -> Unit = {}
): List<DtsNodeSummary> {
    return DeviceTreeParser
        .parse(0, text, checkCancellation)
        .flatten()
        .map { DtsNodeSummary(it.path, it.propertyCount) }
        .sortedBy { it.path }
}
