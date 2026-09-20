package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class DtsNodeSummary(val path: String, val propertyCount: Int)

@Composable
fun DeviceTreeScreen(state: MainUiState, contentPadding: PaddingValues) {
    val workspace = state.workspace
    var query by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        item { Column { Text("设备树", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("当前阶段提供只读浏览与搜索；下一阶段在这里接入通用属性编辑。", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (workspace == null) {
            item { Card(Modifier.fillMaxWidth()) { Text("请先在“概览”加载一个 DTBO 工作区。", modifier = Modifier.padding(18.dp)) } }
        } else {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { workspace.dtsFiles.forEachIndexed { index, _ -> Card(Modifier.clickable { selectedEntry = index }) { Text("Entry " + index, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (selectedEntry == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } } } }
            item { OutlinedTextField(query, { query = it }, label = { Text("搜索节点路径") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            val file = workspace.dtsFiles.getOrNull(selectedEntry)
            val nodes = remember(file, query) { if (file == null || !file.isFile) emptyList() else parseNodeSummaries(file.readText()).filter { query.isBlank() || it.path.contains(query, ignoreCase = true) } }
            item { Text("Entry " + selectedEntry + " · " + nodes.size + " 个节点", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(nodes.take(500)) { node ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.AccountTree, null); Column(Modifier.weight(1f)) { Text(node.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall); Text(node.propertyCount.toString() + " 个直接属性", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            }
            if (nodes.size > 500) item { Text("当前仅展示前 500 个匹配节点，请使用搜索缩小范围。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun parseNodeSummaries(text: String): List<DtsNodeSummary> {
    data class MutableNode(val name: String, val path: String, var properties: Int = 0)
    val stack = mutableListOf<MutableNode>()
    val result = mutableListOf<DtsNodeSummary>()
    val open = Regex("""^\s*(?:[A-Za-z0-9_.-]+:\s*)?([A-Za-z0-9,._@+\-/#]+)\s*\{\s*(?://.*)?$""")
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        val match = open.matchEntire(line)
        if (match != null) {
            val name = match.groupValues[1]
            val parent = stack.lastOrNull()?.path?.trimEnd('/') ?: ""
            val path = if (parent.isEmpty() || name == "/") "/" else parent + "/" + name
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