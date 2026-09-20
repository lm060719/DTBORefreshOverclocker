package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private class NodeLoadResult(
    val nodes: List<DtsNodeSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

@Composable
fun DeviceTreeScreen(state: MainUiState, contentPadding: PaddingValues) {
    val workspace = state.workspace
    var query by rememberSaveable(workspace?.rootDir?.path) { mutableStateOf("") }
    var selectedEntry by rememberSaveable(workspace?.rootDir?.path) { mutableIntStateOf(0) }
    val entry = selectedEntry.coerceIn(0, (workspace?.dtsFiles?.lastIndex ?: 0).coerceAtLeast(0))
    val file = workspace?.dtsFiles?.getOrNull(entry)
    // Staging/reset can rewrite the same file in place. The change list invalidates
    // the index without re-reading it on every search keystroke or log update.
    val loaded by key(file, state.stagedChanges) {
        produceState(NodeLoadResult(), file, state.stagedChanges) {
            value = try {
                val text = withContext(Dispatchers.IO) { file?.readText() }
                val nodes = withContext(Dispatchers.Default) {
                    if (text == null) emptyList() else parseNodeSummaries(text) { ensureActive() }
                }
                NodeLoadResult(nodes, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NodeLoadResult(loading = false, error = error.message ?: "读取失败")
            }
        }
    }
    val filtered by key(loaded, query) {
        produceState(NodeLoadResult(), loaded, query) {
            value = if (loaded.loading || loaded.error != null || query.isBlank()) loaded else {
                delay(120)
                val search = query.trim()
                val matches = withContext(Dispatchers.Default) {
                    loaded.nodes.filter {
                        ensureActive()
                        it.path.contains(search, ignoreCase = true)
                    }
                }
                NodeLoadResult(matches, loading = false)
            }
        }
    }
    val nodes = filtered.nodes
    LazyColumn(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Column { Text("设备树", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("当前阶段提供只读浏览与搜索；下一阶段在这里接入通用属性编辑。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (workspace == null) {
            item { Card(Modifier.fillMaxWidth()) { Text("请先在“概览”加载一个 DTBO 工作区。", modifier = Modifier.padding(18.dp)) } }
        } else {
            item {
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(workspace.dtsFiles.size, key = { workspace.dtsFiles[it].path }) { index ->
                        FilterChip(
                            selected = entry == index,
                            onClick = { selectedEntry = index },
                            label = { Text("Entry $index") }
                        )
                    }
                }
            }
            item { OutlinedTextField(query, { query = it }, label = { Text("搜索节点路径") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                when {
                    filtered.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    filtered.error != null -> Text("设备树读取失败：${filtered.error}", color = MaterialTheme.colorScheme.error)
                    else -> Text("Entry $entry · ${nodes.size} 个节点", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(nodes, key = { it.path }, contentType = { "node" }) { node ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.AccountTree, null); Column(Modifier.weight(1f)) { Text(node.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall); Text(node.propertyCount.toString() + " 个直接属性", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

internal data class DtsNodeSummary(val path: String, val propertyCount: Int)

internal fun parseNodeSummaries(text: String, checkCancellation: () -> Unit = {}): List<DtsNodeSummary> {
    data class MutableNode(val name: String, val path: String, var properties: Int = 0)
    val stack = mutableListOf<MutableNode>()
    val result = mutableListOf<DtsNodeSummary>()
    val open = Regex("""^\s*(?:[A-Za-z0-9_.-]+:\s*)?([A-Za-z0-9,._@+\-/#]+)\s*\{\s*(?://.*)?$""")
    text.lineSequence().forEach { line ->
        checkCancellation()
        val trimmed = line.trim()
        val match = open.matchEntire(line)
        if (match != null) {
            val name = match.groupValues[1]
            val parent = stack.lastOrNull()?.path?.trimEnd('/') ?: ""
            val path = if (name == "/") "/" else "$parent/$name"
            stack += MutableNode(name, path)
        } else if (trimmed.startsWith("};") || trimmed == "}") {
            stack.removeLastOrNull()?.let { result += DtsNodeSummary(it.path, it.properties) }
        } else if (stack.isNotEmpty() && "=" in trimmed && trimmed.endsWith(";")) {
            stack.last().properties++
        } else if (stack.isNotEmpty() && trimmed.endsWith(";") && !trimmed.startsWith("/")) {
            stack.last().properties++
        }
    }
    while (stack.isNotEmpty()) stack.removeLastOrNull()?.let { result += DtsNodeSummary(it.path, it.properties) }
    return result.distinctBy { it.path }.sortedBy { it.path }
}
