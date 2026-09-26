package io.mo.dtbooverclocker.ui

import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNames
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeValueCodec
import io.mo.dtbooverclocker.core.devicetree.NumberBase
import io.mo.dtbooverclocker.core.devicetree.PropertyType

@Composable
internal fun NodeNameDialog(
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
    val nameError = DeviceTreeNames.nodeNameError(name)
        ?: when
        {
            mode == NodeEditMode.RENAME && name == node.name -> "新节点名与原节点名相同"
            mode == NodeEditMode.ADD_CHILD && node.children.any { it.name == name } -> "已存在同名子节点"
            else -> null
        }
    val valid = nameError == null

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
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                    // An empty field is simply unfinished input, not an error worth shouting about.
                    isError = name.isNotEmpty() && nameError != null,
                    supportingText = {
                        Text(
                            nameError?.takeIf { name.isNotEmpty() }
                                ?: "支持 unit-address，例如 timing@3、panel@ae94000"
                        )
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

internal fun parentPathForUi(path: String): String
{
    if (path == "/")
    {
        return "/"
    }
    val parent = path.substringBeforeLast('/')
    return parent.ifEmpty { "/" }
}

@Composable
internal fun PropertyEditorDialog(
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

    val nameError = if (adding)
    {
        val trimmedName = name.trim()
        DeviceTreeNames.propertyNameError(trimmedName)
            ?: "属性已存在".takeIf { node.properties.any { it.name == trimmedName } }
    }
    else
    {
        null
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
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
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
                    isError = adding && name.isNotEmpty() && nameError != null,
                    supportingText = nameError?.takeIf { adding && name.isNotEmpty() }?.let { error ->
                        { Text(error) }
                    },
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
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                            modifier = Modifier.padding(Spacing.md)
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
                enabled = nameError == null && validationError == null
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
