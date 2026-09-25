package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDiff
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReference
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceIndex
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceIndexer
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceKind
import io.mo.dtbooverclocker.core.devicetree.allowedNodePaths
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeValueCodec
import io.mo.dtbooverclocker.core.devicetree.NumberBase
import io.mo.dtbooverclocker.core.devicetree.PropertyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

private data class DocumentLoadResult(
    val document: DeviceTreeDocument? = null,
    val nodes: List<DeviceTreeNode> = emptyList(),
    val nodesByPath: Map<String, DeviceTreeNode> = emptyMap(),
    val referenceIndex: DeviceTreeReferenceIndex? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val referenceError: String? = null
)

private data class DtsFileStamp(
    val lastModified: Long,
    val length: Long
)

private data class CachedDocumentLoad(
    val stamp: DtsFileStamp?,
    val transactionIds: List<String>,
    val result: DocumentLoadResult
)

private data class TreeRow(
    val node: DeviceTreeNode,
    val depth: Int
)

private enum class NodeEditMode
{
    ADD_CHILD,
    CLONE,
    RENAME
}

private enum class DeviceTreeSearchScope(val label: String)
{
    ALL("全部"),
    NODE("节点"),
    PROPERTY("属性"),
    VALUE("值"),
    REFERENCE("引用"),
    MODIFIED("已修改")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceTreeScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onSetProperty: (Int, String, String, String?) -> Unit,
    onAddProperty: (Int, String, String, String?) -> Unit,
    onDeleteProperty: (Int, String, String) -> Unit,
    onAddNode: (Int, String, String) -> Unit,
    onCloneNode: (Int, String, String) -> Unit,
    onRenameNode: (Int, String, String) -> Unit,
    onDeleteNode: (Int, String) -> Unit,
    onUndoChange: (String) -> Unit
) {
    val workspace = state.workspace
    var query by rememberSaveable(workspace?.rootDir?.path) { mutableStateOf("") }
    var searchScope by rememberSaveable(workspace?.rootDir?.path) {
        mutableStateOf(DeviceTreeSearchScope.ALL)
    }
    var selectedEntry by rememberSaveable(workspace?.rootDir?.path) { mutableIntStateOf(0) }
    var selectedNodePath by rememberSaveable(workspace?.rootDir?.path, selectedEntry) { mutableStateOf<String?>(null) }
    var showNodeSheet by remember(workspace?.rootDir?.path, selectedEntry) { mutableStateOf(false) }
    var editorProperty by remember(workspace?.rootDir?.path, selectedEntry) { mutableStateOf<DeviceTreeProperty?>(null) }
    var addingProperty by remember(workspace?.rootDir?.path, selectedEntry) { mutableStateOf(false) }
    var deleteProperty by remember(workspace?.rootDir?.path, selectedEntry) { mutableStateOf<DeviceTreeProperty?>(null) }
    var nodeEditMode by remember(workspace?.rootDir?.path, selectedEntry) { mutableStateOf<NodeEditMode?>(null) }
    var deleteNodePath by remember(workspace?.rootDir?.path, selectedEntry) { mutableStateOf<String?>(null) }

    val entry = selectedEntry.coerceIn(
        0,
        (workspace?.metadata?.entries?.lastIndex ?: 0).coerceAtLeast(0)
    )
    var expandedPaths by rememberSaveable(workspace?.rootDir?.path, entry) {
        mutableStateOf(listOf("/"))
    }

    val file = workspace?.let { File(it.rootDir, "dts/entry_$entry.dts") }?.takeIf { it.isFile }
    val entryTransactionIds = remember(state.transactions, entry) {
        state.transactions.filter { entry in it.entryIndices }.map { it.id }
    }
    val operationInProgress by rememberUpdatedState(state.workspaceOperationInProgress)
    var lastLoad by remember(file) { mutableStateOf<CachedDocumentLoad?>(null) }
    // workspaceRevision bumps after every workspace operation, including ones on other entries.
    // Reparse only when this entry's transactions changed or the file on disk actually changed.
    val loaded by key(file, entryTransactionIds, state.workspaceRevision) {
        produceState(initialValue = lastLoad?.result ?: DocumentLoadResult()) {
            // Keep showing the last document while an operation runs; never read a DTS it may be replacing.
            snapshotFlow { operationInProgress }.first { !it }
            val stamp = withContext(Dispatchers.IO) { file?.let { DtsFileStamp(it.lastModified(), it.length()) } }
            val cached = lastLoad
            if (cached != null && cached.stamp == stamp && cached.transactionIds == entryTransactionIds)
            {
                value = cached.result
                return@produceState
            }

            value = DocumentLoadResult()
            val result = try
            {
                val text = withContext(Dispatchers.IO) { file?.readText() }
                val document = withContext(Dispatchers.Default) {
                    if (text == null)
                    {
                        null
                    }
                    else
                    {
                        DeviceTreeParser.parse(entry, text) { ensureActive() }
                    }
                }

                if (document == null)
                {
                    DocumentLoadResult(document = null, loading = false)
                }
                else
                {
                    val referenceResult = withContext(Dispatchers.Default) {
                        runCatching {
                            DeviceTreeReferenceIndexer.build(document)
                        }
                    }

                    val nodes = withContext(Dispatchers.Default) { document.flatten() }
                    DocumentLoadResult(
                        document = document,
                        nodes = nodes,
                        nodesByPath = withContext(Dispatchers.Default) { nodes.associateBy { it.path } },
                        referenceIndex = referenceResult.getOrNull(),
                        loading = false,
                        referenceError = referenceResult.exceptionOrNull()?.let(::formatReferenceIndexError)
                    )
                }
            }
            catch (cancelled: CancellationException)
            {
                throw cancelled
            }
            catch (error: Exception)
            {
                DocumentLoadResult(loading = false, error = error.message ?: "读取失败")
            }
            value = result
            lastLoad = CachedDocumentLoad(stamp, entryTransactionIds, result)
        }
    }

    val document = loaded.document
    LaunchedEffect(document)
    {
        val currentPath = selectedNodePath
        if (document == null)
        {
            showNodeSheet = false
        }
        else if (currentPath == null || loaded.nodesByPath[currentPath] == null)
        {
            selectedNodePath = document.root.path
        }
    }

    val expandedSet = remember(expandedPaths) { expandedPaths.toSet() }
    val changesForEntry = remember(state.transactions, entry) {
        state.transactions.flatMap { it.operations }.filter { it.entryIndex == entry }
    }
    val modifiedNodePaths = remember(changesForEntry)
    {
        changesForEntry
            .flatMap { change -> listOf(change.nodePath) + change.allowedNodePaths() }
            .toSet()
    }
    val referenceIndex = loaded.referenceIndex
    val filteredMode = query.isNotBlank() || searchScope != DeviceTreeSearchScope.ALL
    val visibleRows by key(document, query, searchScope, expandedSet, referenceIndex, modifiedNodePaths) {
        produceState<List<TreeRow>>(initialValue = emptyList()) {
            if (document == null) return@produceState
            if (query.isNotBlank()) delay(180)
            value = withContext(Dispatchers.Default) {
                val search = query.trim()
                if (filteredMode) {
                    val modifiedIndex = ModifiedPathIndex(modifiedNodePaths)
                    loaded.nodes.asSequence().filter { node ->
                        ensureActive()
                        matchesSearchScope(node, search, searchScope, referenceIndex, modifiedIndex)
                    }.map { TreeRow(it, depthOf(it.path)) }.toList()
                } else {
                    buildVisibleRows(document.root, expandedSet) { ensureActive() }
                }
            }
        }
    }

    val selectedNode = loaded.nodesByPath[selectedNodePath ?: "/"]

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
                Text(
                    "设备树",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "层级浏览、节点详情与类型化属性编辑。自由编辑仍禁止 Root 直刷。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (workspace == null)
        {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "请先在“概览”加载一个 DTBO 工作区。",
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        }
        else
        {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(workspace.metadata.entries.size, key = { it }) { index ->
                        val available = File(workspace.rootDir, "dts/entry_$index.dts").isFile
                        FilterChip(
                            selected = entry == index,
                            onClick = {
                                selectedEntry = index
                                selectedNodePath = null
                                showNodeSheet = false
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DeviceTreeSearchScope.entries, key = { it.name }) { scope ->
                        FilterChip(
                            selected = searchScope == scope,
                            onClick = { searchScope = scope },
                            label = { Text(scope.label) }
                        )
                    }
                }
            }

            item {
                when
                {
                    loaded.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    loaded.error != null -> Text(
                        "设备树读取失败：${loaded.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                    document == null -> Text("该 Entry 无法反编译为可编辑 DTS。")
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Entry $entry · ${loaded.nodes.size} 个节点 · 当前显示 ${visibleRows.size} · " +
                                "${referenceIndex?.references?.size ?: 0} 条引用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        loaded.referenceError?.let { referenceError ->
                            Text(
                                "引用索引已降级：$referenceError。节点浏览和属性编辑仍可继续使用。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        val externalFixupCount = referenceIndex?.externalFixups()?.size ?: 0
                        if (externalFixupCount > 0)
                        {
                            Text(
                                "$externalFixupCount 条外部 Fixup 指向基础设备树，属于 DTBO 正常外部依赖。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val unresolvedCount = referenceIndex?.unresolved()?.size ?: 0
                        if (unresolvedCount > 0)
                        {
                            Text(
                                "存在 $unresolvedCount 条真正未解析的内部引用，可在“引用”筛选中查看。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        if (!filteredMode)
                        {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        expandedPaths = loaded.nodes
                                            .filter { it.children.isNotEmpty() && depthOf(it.path) < 2 }
                                            .map { it.path }
                                            .distinct()
                                    }
                                ) {
                                    Text("展开到 2 级")
                                }
                                TextButton(
                                    onClick = { expandedPaths = listOf("/") }
                                ) {
                                    Text("仅展开根节点")
                                }
                                TextButton(
                                    onClick = {
                                        expandedPaths = loaded.nodes
                                            .filter { it.children.isNotEmpty() }
                                            .map { it.path }
                                            .distinct()
                                    }
                                ) {
                                    Text("全部展开")
                                }
                            }
                        }
                    }
                }
            }

            if (document != null)
            {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (!filteredMode) "节点树" else "筛选结果",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (filteredMode)
                        {
                            Text(
                                "筛选时忽略折叠状态",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(visibleRows, key = { it.node.path }) { row ->
                    TreeNodeCard(
                        row = row,
                        expanded = row.node.path in expandedSet,
                        selected = selectedNodePath == row.node.path,
                        searchMode = filteredMode,
                        onToggle = {
                            if (row.node.children.isNotEmpty())
                            {
                                expandedPaths = if (row.node.path in expandedSet)
                                {
                                    expandedPaths.filterNot { it == row.node.path }
                                }
                                else
                                {
                                    (expandedPaths + row.node.path).distinct()
                                }
                            }
                        },
                        onOpen = {
                            selectedNodePath = row.node.path
                            showNodeSheet = true
                        }
                    )
                }

                if (changesForEntry.isNotEmpty())
                {
                    item { HorizontalDivider() }
                    item {
                        Text(
                            "暂存 Diff · ${changesForEntry.size} 个底层设备树操作",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(changesForEntry, key = { it.id }) { change ->
                        ChangeCard(
                            change = change,
                            undoEnabled = !state.busy && state.transactions.lastOrNull()?.let {
                                it.kind == io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransactionKind.GENERIC_EDIT &&
                                    it.operations.singleOrNull()?.id == change.id
                            } == true,
                            onUndo = { onUndoChange(change.id) }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showNodeSheet && selectedNode != null)
    {
        ModalBottomSheet(
            onDismissRequest = { showNodeSheet = false }
        ) {
            NodeDetailSheet(
                node = selectedNode,
                onOpenChild = { child ->
                    selectedNodePath = child.path
                },
                onEdit = { property ->
                    editorProperty = property
                    addingProperty = false
                    showNodeSheet = false
                },
                onDelete = { property ->
                    deleteProperty = property
                    showNodeSheet = false
                },
                onAdd = {
                    editorProperty = null
                    addingProperty = true
                    showNodeSheet = false
                },
                onAddChild = {
                    nodeEditMode = NodeEditMode.ADD_CHILD
                    showNodeSheet = false
                },
                onClone = {
                    nodeEditMode = NodeEditMode.CLONE
                    showNodeSheet = false
                },
                onRename = {
                    nodeEditMode = NodeEditMode.RENAME
                    showNodeSheet = false
                },
                onDeleteNode = {
                    deleteNodePath = selectedNode.path
                    showNodeSheet = false
                },
                referenceIndex = referenceIndex,
                onNavigateReference = { path ->
                    selectedNodePath = path
                    expandedPaths = (
                        expandedPaths +
                            ancestorPaths(path)
                        ).distinct()
                }
            )
        }
    }

    if (selectedNode != null && (addingProperty || editorProperty != null))
    {
        PropertyEditorDialog(
            node = selectedNode,
            property = editorProperty,
            adding = addingProperty,
            onDismiss = {
                addingProperty = false
                editorProperty = null
            },
            onConfirm = { name, rawValue ->
                if (addingProperty)
                {
                    onAddProperty(entry, selectedNode.path, name, rawValue)
                }
                else
                {
                    onSetProperty(entry, selectedNode.path, name, rawValue)
                }
                addingProperty = false
                editorProperty = null
            }
        )
    }

    if (selectedNode != null && deleteProperty != null)
    {
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
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteProperty = null }) {
                    Text("取消")
                }
            }
        )
    }


    if (selectedNode != null && nodeEditMode != null)
    {
        NodeNameDialog(
            mode = nodeEditMode!!,
            node = selectedNode,
            onDismiss = { nodeEditMode = null },
            onConfirm = { name ->
                when (nodeEditMode)
                {
                    NodeEditMode.ADD_CHILD ->
                    {
                        expandedPaths = (expandedPaths + selectedNode.path).distinct()
                        onAddNode(entry, selectedNode.path, name)
                    }
                    NodeEditMode.CLONE ->
                    {
                        val parent = parentPathForUi(selectedNode.path)
                        expandedPaths = (expandedPaths + parent).distinct()
                        onCloneNode(entry, selectedNode.path, name)
                    }
                    NodeEditMode.RENAME ->
                    {
                        onRenameNode(entry, selectedNode.path, name)
                    }
                    null -> Unit
                }
                nodeEditMode = null
            }
        )
    }

    if (deleteNodePath != null)
    {
        val targetPath = deleteNodePath!!
        AlertDialog(
            onDismissRequest = { deleteNodePath = null },
            title = { Text("删除节点") },
            text = {
                Text(
                    "$targetPath\n\n将删除该节点及其全部子节点和属性。修改会进入暂存区，且仅最近一项修改可直接撤销。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteNode(entry, targetPath)
                        deleteNodePath = null
                    }
                ) {
                    Text("删除整个节点")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteNodePath = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun TreeNodeCard(
    row: TreeRow,
    expanded: Boolean,
    selected: Boolean,
    searchMode: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val node = row.node
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (searchMode) 0.dp else (row.depth * 12).dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
            {
                MaterialTheme.colorScheme.primaryContainer
            }
            else
            {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(vertical = 7.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.children.isNotEmpty() && !searchMode)
            {
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (expanded) "折叠节点" else "展开节点"
                    )
                }
            }
            else
            {
                Spacer(Modifier.width(36.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        node.name,
                        modifier = Modifier.weight(1f, fill = false),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    node.label?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$it:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    node.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                "${node.propertyCount}P · ${node.childCount}N",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NodeDetailSheet(
    node: DeviceTreeNode,
    onOpenChild: (DeviceTreeNode) -> Unit,
    onEdit: (DeviceTreeProperty) -> Unit,
    onDelete: (DeviceTreeProperty) -> Unit,
    onAdd: () -> Unit,
    onAddChild: () -> Unit,
    onClone: () -> Unit,
    onRename: () -> Unit,
    onDeleteNode: () -> Unit,
    referenceIndex: DeviceTreeReferenceIndex?,
    onNavigateReference: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            node.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            node.path,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        node.label?.let {
            Text(
                "Label · $it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            "${node.propertyCount} 个直接属性 · ${node.childCount} 个直接子节点",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("新增属性")
        }

        Text(
            "节点操作",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = onAddChild,
                label = { Text("新增子节点") }
            )
            if (node.path != "/")
            {
                AssistChip(
                    onClick = onClone,
                    label = { Text("克隆节点") }
                )
                AssistChip(
                    onClick = onRename,
                    label = { Text("重命名") }
                )
                AssistChip(
                    onClick = onDeleteNode,
                    label = { Text("删除节点") }
                )
            }
        }

        ReferenceSection(
            node = node,
            referenceIndex = referenceIndex,
            onNavigate = onNavigateReference
        )

        if (node.children.isNotEmpty())
        {
            Text(
                "子节点",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            node.children.forEach { child ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChild(child) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                child.name,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${child.propertyCount}P · ${child.childCount}N",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.KeyboardArrowRight, "进入子节点")
                    }
                }
            }
        }

        Text(
            "属性",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (node.properties.isEmpty())
        {
            Text(
                "该节点没有直接属性。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else
        {
            node.properties.forEach { property ->
                PropertyCard(
                    property = property,
                    onEdit = { onEdit(property) },
                    onDelete = { onDelete(property) }
                )
            }
        }
    }
}


@Composable
private fun ReferenceSection(
    node: DeviceTreeNode,
    referenceIndex: DeviceTreeReferenceIndex?,
    onNavigate: (String) -> Unit
)
{
    if (referenceIndex == null)
    {
        return
    }

    val aliases = referenceIndex.labels
        .filterValues { it == node.path }
        .keys
        .sorted()
    val phandles = referenceIndex.phandles
        .filterValues { it == node.path }
        .keys
        .sorted()
    val outgoing = referenceIndex.outgoing(node.path)
    val incoming = referenceIndex.incoming(node.path)

    if (aliases.isEmpty() && phandles.isEmpty() && outgoing.isEmpty() && incoming.isEmpty())
    {
        return
    }

    HorizontalDivider()
    Text(
        "引用关系",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )

    if (aliases.isNotEmpty())
    {
        Text(
            "Label · " + aliases.joinToString { "&$it" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    if (phandles.isNotEmpty())
    {
        Text(
            "Phandle · " + phandles.joinToString { "0x" + it.toString(16) },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (outgoing.isNotEmpty())
    {
        Text(
            "引用出去 · ${outgoing.size}",
            style = MaterialTheme.typography.labelLarge
        )
        outgoing.forEach { reference ->
            ReferenceCard(
                reference = reference,
                incoming = false,
                onNavigate = onNavigate
            )
        }
    }

    if (incoming.isNotEmpty())
    {
        Text(
            "被引用 · ${incoming.size}",
            style = MaterialTheme.typography.labelLarge
        )
        incoming.forEach { reference ->
            ReferenceCard(
                reference = reference,
                incoming = true,
                onNavigate = onNavigate
            )
        }
    }
}

@Composable
private fun ReferenceCard(
    reference: DeviceTreeReference,
    incoming: Boolean,
    onNavigate: (String) -> Unit
)
{
    val target = if (incoming)
    {
        reference.sourceNodePath
    }
    else
    {
        reference.targetNodePath
    }
    val kind = when (reference.kind)
    {
        DeviceTreeReferenceKind.LABEL -> "Label 引用"
        DeviceTreeReferenceKind.PATH -> "路径引用"
        DeviceTreeReferenceKind.LOCAL_FIXUP -> "本地 Fixup 引用"
        DeviceTreeReferenceKind.EXTERNAL_FIXUP -> "外部 Fixup"
        DeviceTreeReferenceKind.NUMERIC_CANDIDATE -> "数值 phandle 候选"
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (target != null)
                {
                    Modifier.clickable { onNavigate(target) }
                }
                else
                {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    if (incoming)
                    {
                        "${reference.sourceNodePath}/${reference.propertyName}"
                    }
                    else
                    {
                        "${reference.propertyName} · ${reference.token}"
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    buildString {
                        append(kind)
                        append(" · ")
                        append(
                            if (reference.resolved)
                            {
                                reference.targetNodePath
                            }
                            else if (reference.kind == DeviceTreeReferenceKind.EXTERNAL_FIXUP)
                            {
                                "基础设备树外部符号"
                            }
                            else
                            {
                                "未解析"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (reference.kind == DeviceTreeReferenceKind.NUMERIC_CANDIDATE)
                    {
                        MaterialTheme.colorScheme.tertiary
                    }
                    else
                    {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (target != null)
            {
                Icon(Icons.Default.KeyboardArrowRight, "跳转到引用节点")
            }
        }
    }
}

@Composable
private fun NodeNameDialog(
    mode: NodeEditMode,
    node: DeviceTreeNode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
)
{
    val initial = when (mode)
    {
        NodeEditMode.ADD_CHILD -> ""
        NodeEditMode.CLONE -> node.name + "_copy"
        NodeEditMode.RENAME -> node.name
    }
    var name by remember(mode, node.path) { mutableStateOf(initial) }
    val valid = name.isNotBlank() &&
        name != "/" &&
        Regex("^[A-Za-z0-9,._@+#-]+$").matches(name) &&
        (mode != NodeEditMode.RENAME || name != node.name)

    val title = when (mode)
    {
        NodeEditMode.ADD_CHILD -> "新增子节点"
        NodeEditMode.CLONE -> "克隆节点"
        NodeEditMode.RENAME -> "重命名节点"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    node.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
                if (mode == NodeEditMode.CLONE)
                {
                    Text(
                        "克隆会复制整个节点子树。当前阶段包含 label、phandle 或 linux,phandle 的子树会被安全阻止，避免重复节点身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("节点名") },
                    supportingText = {
                        Text("支持 unit-address，例如 timing@3、panel@ae94000")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = valid
            ) {
                Text("暂存修改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun parentPathForUi(path: String): String
{
    if (path == "/")
    {
        return "/"
    }
    val parent = path.substringBeforeLast('/')
    return parent.ifEmpty { "/" }
}

@Composable
private fun PropertyCard(
    property: DeviceTreeProperty,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    property.name,
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    DeviceTreeValueCodec.displayName(property.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (DeviceTreeValueCodec.supportsTypedEditor(property.type, property.rawValue))
                    {
                        MaterialTheme.colorScheme.primary
                    }
                    else
                    {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Text(
                property.displayValue.ifBlank { "<boolean>" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                property.rawStatement,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (property.type != PropertyType.BOOLEAN)
                {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(5.dp))
                        Text("编辑")
                    }
                }
                else
                {
                    Text(
                        "Boolean 无值，删除即关闭",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Spacer(Modifier.width(5.dp))
                    Text("删除")
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
    val addTypes = remember {
        listOf(
            PropertyType.U32,
            PropertyType.STRING,
            PropertyType.STRING_LIST,
            PropertyType.CELLS,
            PropertyType.BYTE_ARRAY,
            PropertyType.BOOLEAN,
            PropertyType.UNKNOWN
        )
    }

    var name by remember(property, adding) {
        mutableStateOf(property?.name.orEmpty())
    }
    var selectedType by remember(property, adding) {
        mutableStateOf(property?.type ?: PropertyType.U32)
    }
    var numberBase by remember(property, adding) {
        mutableStateOf(DeviceTreeValueCodec.preferredNumberBase(property))
    }
    var rawMode by remember(property, adding) {
        mutableStateOf(property != null && !DeviceTreeValueCodec.supportsTypedEditor(property.type, property.rawValue))
    }
    var typedText by remember(property, adding) {
        mutableStateOf(
            DeviceTreeValueCodec.editableText(
                property?.type ?: PropertyType.U32,
                property?.rawValue,
                DeviceTreeValueCodec.preferredNumberBase(property)
            )
        )
    }
    var rawText by remember(property, adding) {
        mutableStateOf(property?.rawValue.orEmpty())
    }

    val typedSupported = DeviceTreeValueCodec.supportsTypedEditor(selectedType, if (rawMode) rawText.takeIf { it.isNotBlank() } else property?.rawValue)
    val useRaw = rawMode || !typedSupported
    val validationError = remember(selectedType, typedText, rawText, useRaw)
    {
        if (useRaw)
        {
            if (selectedType == PropertyType.BOOLEAN)
            {
                null
            }
            else if (rawText.trim().isEmpty())
            {
                "Raw DTS 值不能为空"
            }
            else
            {
                DeviceTreeEditor.rawValueError(rawText)
            }
        }
        else
        {
            DeviceTreeValueCodec.validate(selectedType, typedText)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "新增属性" else "编辑属性") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

                if (adding)
                {
                    Text(
                        "属性类型",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        addTypes.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = {
                                    selectedType = type
                                    rawMode = !DeviceTreeValueCodec.supportsTypedEditor(type)
                                },
                                label = { Text(DeviceTreeValueCodec.displayName(type)) }
                            )
                        }
                    }
                }
                else
                {
                    Text(
                        "类型 · ${DeviceTreeValueCodec.displayName(selectedType)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (typedSupported && selectedType != PropertyType.BOOLEAN)
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Raw DTS 模式",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "关闭时由编辑器自动生成 DTS 语法",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rawMode,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    runCatching { DeviceTreeValueCodec.encode(selectedType, typedText, numberBase) }
                                        .onSuccess { rawText = it.orEmpty(); rawMode = true }
                                } else {
                                    typedText = DeviceTreeValueCodec.editableText(selectedType, rawText, numberBase)
                                    rawMode = false
                                }
                            }
                        )
                    }
                }

                if (!useRaw && selectedType == PropertyType.U32)
                {
                    Text(
                        "输出进制",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = numberBase == NumberBase.DECIMAL,
                            onClick = { numberBase = NumberBase.DECIMAL },
                            label = { Text("十进制") }
                        )
                        FilterChip(
                            selected = numberBase == NumberBase.HEX,
                            onClick = { numberBase = NumberBase.HEX },
                            label = { Text("HEX") }
                        )
                    }
                }

                if (selectedType == PropertyType.BOOLEAN && !useRaw)
                {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "Boolean 属性只有“存在 / 不存在”，没有数值。新增后会生成：$name;",
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                else if (useRaw)
                {
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        label = { Text("Raw DTS 值") },
                        supportingText = {
                            Text("例如 <0x78>、\"qcom,panel\"、<&label>、[01 ff]")
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else
                {
                    TypedValueField(
                        type = selectedType,
                        value = typedText,
                        onValueChange = { typedText = it }
                    )
                }

                validationError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (!useRaw)
                {
                    val preview = runCatching {
                        DeviceTreeValueCodec.encode(selectedType, typedText, numberBase)
                    }.getOrNull()
                    Text(
                        "DTS 预览：$name${preview?.let { " = $it" } ?: ""};",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rawValue = if (useRaw)
                    {
                        if (selectedType == PropertyType.BOOLEAN)
                        {
                            null
                        }
                        else
                        {
                            rawText.trim().removeSuffix(";").trim().takeIf { it.isNotEmpty() }
                        }
                    }
                    else
                    {
                        DeviceTreeValueCodec.encode(selectedType, typedText, numberBase)
                    }
                    onConfirm(name.trim(), rawValue)
                },
                enabled = name.isNotBlank() && validationError == null
            ) {
                Text("暂存修改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun TypedValueField(
    type: PropertyType,
    value: String,
    onValueChange: (String) -> Unit
) {
    when (type)
    {
        PropertyType.STRING ->
        {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("字符串") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        PropertyType.STRING_LIST ->
        {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("字符串列表") },
                supportingText = { Text("每行一项，保存时自动生成带引号列表") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }
        PropertyType.U32 ->
        {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("U32 数值") },
                supportingText = { Text("可输入 144 或 0x90，范围 0～4294967295") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        PropertyType.U64,
        PropertyType.CELLS ->
        {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Cell 列表") },
                supportingText = { Text("使用空格分隔，例如 0x1 0x2 144") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        PropertyType.BYTE_ARRAY ->
        {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Byte Array") },
                supportingText = { Text("两位 HEX，空格分隔，例如 01 ff a0") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        PropertyType.BOOLEAN,
        PropertyType.PHANDLE,
        PropertyType.UNKNOWN -> Unit
    }
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
                    if (undoEnabled)
                    {
                        "可撤销最近一项修改"
                    }
                    else
                    {
                        "后续已有修改，当前项不可单独撤销"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onUndo, enabled = undoEnabled) {
                    Text("撤销")
                }
            }
        }
    }
}

private fun formatReferenceIndexError(error: Throwable): String
{
    val chain = generateSequence(error) { it.cause }
        .take(4)
        .map { throwable ->
            val type = throwable::class.java.simpleName.ifBlank { throwable::class.java.name }
            val message = throwable.message?.trim().orEmpty()
            if (message.isBlank()) type else "$type: $message"
        }
        .distinct()
        .toList()

    return chain.joinToString(" ← ").ifBlank { error::class.java.name }
}

/** O(depth) lookup for "node is, contains, or lies inside a modified node". */
internal class ModifiedPathIndex(private val modifiedPaths: Set<String>)
{
    private val modifiedAncestors: Set<String> = modifiedPaths.flatMapTo(HashSet(), ::ancestorPaths)

    fun isRelated(path: String): Boolean
    {
        return path in modifiedPaths ||
            path in modifiedAncestors ||
            ancestorPaths(path).any { it in modifiedPaths }
    }
}

private fun matchesSearchScope(
    node: DeviceTreeNode,
    query: String,
    scope: DeviceTreeSearchScope,
    referenceIndex: DeviceTreeReferenceIndex?,
    modifiedPaths: ModifiedPathIndex
): Boolean
{
    // Each predicate is evaluated lazily so a scope only pays for the checks it actually needs.
    fun nodeMatch() = query.isBlank() ||
        node.path.contains(query, ignoreCase = true) ||
        node.name.contains(query, ignoreCase = true) ||
        node.label?.contains(query, ignoreCase = true) == true

    fun propertyMatch() = query.isBlank() || node.properties.any { property ->
        property.name.contains(query, ignoreCase = true)
    }

    fun valueMatch() = query.isBlank() || node.properties.any { property ->
        property.rawValue?.contains(query, ignoreCase = true) == true ||
            property.displayValue.contains(query, ignoreCase = true)
    }

    fun referenceMatch(): Boolean
    {
        val index = referenceIndex ?: return false
        val references = index.outgoing(node.path).asSequence() + index.incoming(node.path).asSequence()
        return references.any { reference ->
            query.isBlank() ||
                reference.token.contains(query, ignoreCase = true) ||
                reference.propertyName.contains(query, ignoreCase = true) ||
                reference.sourceNodePath.contains(query, ignoreCase = true) ||
                reference.targetNodePath?.contains(query, ignoreCase = true) == true
        }
    }

    return when (scope)
    {
        DeviceTreeSearchScope.ALL -> nodeMatch() || propertyMatch() || valueMatch() || referenceMatch()
        DeviceTreeSearchScope.NODE -> nodeMatch()
        DeviceTreeSearchScope.PROPERTY -> propertyMatch()
        DeviceTreeSearchScope.VALUE -> valueMatch()
        DeviceTreeSearchScope.REFERENCE -> referenceMatch()
        DeviceTreeSearchScope.MODIFIED -> modifiedPaths.isRelated(node.path) &&
            (nodeMatch() || propertyMatch() || valueMatch())
    }
}

private fun ancestorPaths(path: String): List<String>
{
    if (path == "/")
    {
        return listOf("/")
    }

    val segments = path.trim('/').split('/')
    val result = mutableListOf("/")
    var current = ""
    segments.dropLast(1).forEach { segment ->
        current += "/$segment"
        result += current
    }
    return result
}

private fun buildVisibleRows(
    root: DeviceTreeNode,
    expandedPaths: Set<String>,
    checkCancellation: () -> Unit = {}
): List<TreeRow>
{
    val result = mutableListOf<TreeRow>()

    fun walk(node: DeviceTreeNode, depth: Int)
    {
        checkCancellation()
        result += TreeRow(node, depth)
        if (node.path in expandedPaths)
        {
            node.children.forEach { child ->
                walk(child, depth + 1)
            }
        }
    }

    walk(root, 0)
    return result
}

private fun depthOf(path: String): Int
{
    if (path == "/")
    {
        return 0
    }
    return path.trim('/').split('/').size
}

internal data class DtsNodeSummary(
    val path: String,
    val propertyCount: Int
)

internal fun parseNodeSummaries(
    text: String,
    checkCancellation: () -> Unit = {}
): List<DtsNodeSummary>
{
    return DeviceTreeParser
        .parse(0, text, checkCancellation)
        .flatten()
        .map { DtsNodeSummary(it.path, it.propertyCount) }
        .sortedBy { it.path }
}
