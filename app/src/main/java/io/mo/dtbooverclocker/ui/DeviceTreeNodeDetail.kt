package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.clickable
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDiff
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReference
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceIndex
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceKind
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeValueCodec
import io.mo.dtbooverclocker.core.devicetree.PropertyType

@Composable
internal fun NodeDetailSheet(
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
    // Lazy: __symbols__ / __fixups__ nodes can carry thousands of properties and references.
    val listState = remember(node.path) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
            }
        }

        referenceItems(
            node = node,
            referenceIndex = referenceIndex,
            onNavigate = onNavigateReference
        )

        if (node.children.isNotEmpty())
        {
            item(key = "children-title") {
                Text(
                    "子节点",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(node.children, key = { "child:${it.startOffset}" }) { child ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChild(child) }
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
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

        item(key = "properties-title") {
            Text(
                "属性",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (node.properties.isEmpty())
        {
            item(key = "properties-empty") {
                Text(
                    "该节点没有直接属性。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else
        {
            items(node.properties, key = { "property:${it.startOffset}" }) { property ->
                PropertyCard(
                    property = property,
                    onEdit = { onEdit(property) },
                    onDelete = { onDelete(property) }
                )
            }
        }
    }
}

private fun LazyListScope.referenceItems(
    node: DeviceTreeNode,
    referenceIndex: DeviceTreeReferenceIndex?,
    onNavigate: (String) -> Unit
)
{
    if (referenceIndex == null)
    {
        return
    }

    val aliases = referenceIndex.labelsOf(node.path)
    val phandles = referenceIndex.phandlesOf(node.path)
    val outgoing = referenceIndex.outgoing(node.path)
    val incoming = referenceIndex.incoming(node.path)

    if (aliases.isEmpty() && phandles.isEmpty() && outgoing.isEmpty() && incoming.isEmpty())
    {
        return
    }

    item(key = "references-header") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
        }
    }

    if (outgoing.isNotEmpty())
    {
        item(key = "references-outgoing") {
            Text(
                "引用出去 · ${outgoing.size}",
                style = MaterialTheme.typography.labelLarge
            )
        }
        items(outgoing.size) { index ->
            ReferenceCard(
                reference = outgoing[index],
                incoming = false,
                onNavigate = onNavigate
            )
        }
    }

    if (incoming.isNotEmpty())
    {
        item(key = "references-incoming") {
            Text(
                "被引用 · ${incoming.size}",
                style = MaterialTheme.typography.labelLarge
            )
        }
        items(incoming.size) { index ->
            ReferenceCard(
                reference = incoming[index],
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
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
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
private fun PropertyCard(
    property: DeviceTreeProperty,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
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
                            .padding(start = Spacing.sm),
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
internal fun ChangeCard(
    row: StagedChangeRow,
    undoEnabled: Boolean,
    onUndo: () -> Unit
) {
    val change = row.change
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                    if (row.laterTransactionCount == 0)
                    {
                        "${row.transaction.kind.displayName} · 最近事务，可直接撤销"
                    }
                    else
                    {
                        "${row.transaction.kind.displayName} · 撤销会连带之后的 ${row.laterTransactionCount} 个事务"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onUndo, enabled = undoEnabled) {
                    Text(if (row.laterTransactionCount == 0) "撤销" else "撤销到此处")
                }
            }
        }
    }
}
