package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Surface
import io.mo.dtbooverclocker.ui.components.EmptyState
import io.mo.dtbooverclocker.ui.components.HintText
import io.mo.dtbooverclocker.ui.components.NoticeBanner
import io.mo.dtbooverclocker.ui.components.SectionCard
import io.mo.dtbooverclocker.ui.components.Tone
import io.mo.dtbooverclocker.ui.theme.AppTheme
import androidx.compose.foundation.clickable
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
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
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParseWarning
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceIndex
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceIndexer
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.core.devicetree.allowedNodePaths
import io.mo.dtbooverclocker.ui.i18n.AppStrings
import io.mo.dtbooverclocker.ui.i18n.I18n
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

internal data class StagedChangeRow(
    val transaction: DeviceTreeTransaction,
    val change: DeviceTreeChange,
    val laterTransactionCount: Int
)

private data class TreeRow(
    val node: DeviceTreeNode,
    val depth: Int
)

internal enum class NodeEditMode
{
    ADD_CHILD,
    CLONE,
    RENAME
}

private enum class DeviceTreeSearchScope
{
    ALL,
    NODE,
    PROPERTY,
    VALUE,
    REFERENCE,
    MODIFIED;

    fun getLabel(strings: AppStrings): String = when (this) {
        ALL -> strings.searchScopeAll
        NODE -> strings.searchScopeNode
        PROPERTY -> strings.searchScopeProperty
        VALUE -> strings.searchScopeValue
        REFERENCE -> strings.searchScopeReference
        MODIFIED -> strings.searchScopeModified
    }
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
    onUndoThroughTransaction: (String) -> Unit
) {
    val strings = I18n.current
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
    var undoConfirmTransactionId by remember(workspace?.rootDir?.path) { mutableStateOf<String?>(null) }

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
    val stagedChangesForEntry = remember(state.transactions, entry) {
        state.transactions.flatMapIndexed { index, transaction ->
            transaction.operations
                .filter { it.entryIndex == entry }
                .map { StagedChangeRow(transaction, it, laterTransactionCount = state.transactions.lastIndex - index) }
        }
    }
    val changesForEntry = remember(stagedChangesForEntry) { stagedChangesForEntry.map { it.change } }
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
            .padding(contentPadding),
        contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        item {
            Column(
                Modifier.padding(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(strings.deviceTreeTitle, style = MaterialTheme.typography.headlineSmall)
                HintText("层级浏览、节点详情与类型化属性编辑。")
            }
        }

        if (workspace == null)
        {
            item {
                EmptyState(
                    Icons.Default.FolderOpen,
                    "还没有工作区",
                    "请先在“概览”加载一个 DTBO 工作区。"
                )
            }
        }
        else
        {
            item {
              SectionCard(Modifier.padding(bottom = Spacing.sm)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text(strings.searchNodes) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(DeviceTreeSearchScope.entries, key = { it.name }) { scope ->
                        FilterChip(
                            selected = searchScope == scope,
                            onClick = { searchScope = scope },
                            label = { Text(scope.getLabel(strings)) }
                        )
                    }
                }
              }
            }

            item {
                when
                {
                    loaded.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    loaded.error != null -> NoticeBanner("设备树读取失败：${loaded.error}", tone = Tone.Danger)
                    document == null -> NoticeBanner("该 Entry 无法反编译为可编辑 DTS。", tone = Tone.Warning)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "Entry $entry · ${loaded.nodes.size} 个节点 · 当前显示 ${visibleRows.size} · " +
                                "${referenceIndex?.references?.size ?: 0} 条引用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        loaded.referenceError?.let { referenceError ->
                            NoticeBanner("引用索引已降级：$referenceError。节点浏览和属性编辑仍可继续使用。", tone = Tone.Warning)
                        }
                        ParseWarningsText(document.warnings)

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
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                        modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs, top = Spacing.sm, bottom = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (!filteredMode) "节点树" else "筛选结果",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
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
                        modified = row.node.path in modifiedNodePaths,
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
                    item {
                        Text(
                            "暂存 Diff · ${changesForEntry.size} 个底层设备树操作",
                            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.lg, bottom = Spacing.xs),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(stagedChangesForEntry, key = { it.change.id }) { row ->
                        ChangeCard(
                            row = row,
                            undoEnabled = !state.busy,
                            onUndo = {
                                if (row.laterTransactionCount == 0)
                                {
                                    onUndoThroughTransaction(row.transaction.id)
                                }
                                else
                                {
                                    undoConfirmTransactionId = row.transaction.id
                                }
                            }
                        )
                    }
                }
            }
        }

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
            title = { Text(strings.deleteProperty) },
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
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteProperty = null }) {
                    Text(strings.cancel)
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

    val undoConfirmIndex = state.transactions.indexOfFirst { it.id == undoConfirmTransactionId }
    if (undoConfirmIndex >= 0)
    {
        val undone = state.transactions.drop(undoConfirmIndex)
        AlertDialog(
            onDismissRequest = { undoConfirmTransactionId = null },
            title = { Text("撤销 ${undone.size} 个事务") },
            text = {
                Text(
                    "为保持事务顺序，撤销此修改会同时撤销之后暂存的全部事务（包括其他 Entry 与功能模块的修改）：\n\n" +
                        undone.take(6).joinToString("\n") { "• ${it.kind.displayName} · ${it.summary}" } +
                        if (undone.size > 6) "\n… 等 ${undone.size} 个" else ""
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUndoThroughTransaction(undone.first().id)
                        undoConfirmTransactionId = null
                    },
                    enabled = !state.busy
                ) {
                    Text("全部撤销")
                }
            },
            dismissButton = {
                TextButton(onClick = { undoConfirmTransactionId = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (deleteNodePath != null)
    {
        val targetPath = deleteNodePath!!
        AlertDialog(
            onDismissRequest = { deleteNodePath = null },
            title = { Text(strings.deleteNode) },
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
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteNodePath = null }) {
                    Text(strings.cancel)
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
    modified: Boolean,
    searchMode: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val node = row.node
    val scheme = MaterialTheme.colorScheme
    val indent = if (searchMode) 0 else minOf(row.depth, MAX_INDENT_DEPTH)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩进引导线：每级一条细线，深层路径不至于把节点名挤出屏幕。
        repeat(indent) {
            Box(Modifier.width(12.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.medium,
            color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
            border = if (selected) BorderStroke(1.dp, scheme.primary) else null
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
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
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            node.name,
                            modifier = Modifier.weight(1f, fill = false),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        node.label?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$it:",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary
                            )
                        }
                        if (modified)
                        {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(8.dp).background(AppTheme.status.warning, CircleShape))
                        }
                    }
                    Text(
                        node.path,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    buildString {
                        if (!searchMode && row.depth > MAX_INDENT_DEPTH) append("L${row.depth} · ")
                        append("${node.propertyCount}P · ${node.childCount}N")
                    },
                    modifier = Modifier.padding(end = Spacing.sm),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val MAX_INDENT_DEPTH = 6

@Composable
private fun ParseWarningsText(warnings: List<DeviceTreeParseWarning>)
{
    if (warnings.isEmpty())
    {
        return
    }
    // An unrecognised node header can shift every later node, so it deserves the error colour.
    val structural = warnings.any { it.reason.contains("节点头") }
    NoticeBanner(
        buildString {
            append("解析提示：${warnings.size} 处语句未被编辑器识别，树中看不到它们，但 dtc 编译时仍会生效。")
            warnings.take(3).forEach { warning ->
                append("\n· 第 ${warning.lineNumber} 行 ${warning.reason}：${warning.statement}")
            }
            if (warnings.size > 3)
            {
                append("\n…")
            }
        },
        tone = if (structural) Tone.Danger else Tone.Warning
    )
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
