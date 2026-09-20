package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeEditor
{
    fun apply(text: String, change: DeviceTreeChange): String
    {
        val document = DeviceTreeParser.parse(change.entryIndex, text)
        val node = requireNotNull(document.findNode(change.nodePath)) {
            "设备树节点不存在：${change.nodePath}"
        }

        return when (change)
        {
            is SetPropertyChange -> setProperty(text, node, change)
            is AddPropertyChange -> addProperty(text, node, change)
            is DeletePropertyChange -> deleteProperty(text, node, change)
        }
    }

    fun buildSetChange(
        entryIndex: Int,
        text: String,
        nodePath: String,
        propertyName: String,
        newRawValue: String?
    ): SetPropertyChange
    {
        val property = requireProperty(entryIndex, text, nodePath, propertyName)
        return SetPropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = propertyName,
            oldRawValue = property.rawValue,
            newRawValue = normalizeRawValue(newRawValue)
        )
    }

    fun buildAddChange(
        entryIndex: Int,
        text: String,
        nodePath: String,
        propertyName: String,
        newRawValue: String?
    ): AddPropertyChange
    {
        validatePropertyName(propertyName)
        val document = DeviceTreeParser.parse(entryIndex, text)
        val node = requireNotNull(document.findNode(nodePath)) { "设备树节点不存在：$nodePath" }
        require(node.properties.none { it.name == propertyName }) { "属性已存在：$nodePath/$propertyName" }
        return AddPropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = propertyName,
            newRawValue = normalizeRawValue(newRawValue)
        )
    }

    fun buildDeleteChange(
        entryIndex: Int,
        text: String,
        nodePath: String,
        propertyName: String
    ): DeletePropertyChange
    {
        val property = requireProperty(entryIndex, text, nodePath, propertyName)
        return DeletePropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = propertyName,
            oldRawValue = property.rawValue
        )
    }

    private fun setProperty(text: String, node: DeviceTreeNode, change: SetPropertyChange): String
    {
        val property = requireNotNull(node.properties.firstOrNull { it.name == change.propertyName }) {
            "属性不存在：${change.nodePath}/${change.propertyName}"
        }
        val replacement = statement(property.indent, change.propertyName, change.newRawValue)
        return text.replaceRange(property.startOffset, property.endOffsetExclusive, replacement)
    }

    private fun addProperty(text: String, node: DeviceTreeNode, change: AddPropertyChange): String
    {
        require(node.properties.none { it.name == change.propertyName }) {
            "属性已存在：${change.nodePath}/${change.propertyName}"
        }
        validatePropertyName(change.propertyName)
        val childIndent = node.properties.firstOrNull()?.indent
            ?: node.children.firstOrNull()?.indent
            ?: (node.indent + "\t")
        val insertion = statement(childIndent, change.propertyName, change.newRawValue) + "\n"
        return text.replaceRange(node.closeStartOffset, node.closeStartOffset, insertion)
    }

    private fun deleteProperty(text: String, node: DeviceTreeNode, change: DeletePropertyChange): String
    {
        val property = requireNotNull(node.properties.firstOrNull { it.name == change.propertyName }) {
            "属性不存在：${change.nodePath}/${change.propertyName}"
        }
        var end = property.endOffsetExclusive
        if (end < text.length && text[end] == '\r')
        {
            end++
        }
        if (end < text.length && text[end] == '\n')
        {
            end++
        }
        return text.removeRange(property.startOffset, end)
    }

    private fun requireProperty(
        entryIndex: Int,
        text: String,
        nodePath: String,
        propertyName: String
    ): DeviceTreeProperty
    {
        val document = DeviceTreeParser.parse(entryIndex, text)
        val node = requireNotNull(document.findNode(nodePath)) { "设备树节点不存在：$nodePath" }
        return requireNotNull(node.properties.firstOrNull { it.name == propertyName }) {
            "属性不存在：$nodePath/$propertyName"
        }
    }

    private fun statement(indent: String, propertyName: String, rawValue: String?): String
    {
        validatePropertyName(propertyName)
        val normalized = normalizeRawValue(rawValue)
        return if (normalized == null)
        {
            "$indent$propertyName;"
        }
        else
        {
            "$indent$propertyName = $normalized;"
        }
    }

    private fun normalizeRawValue(rawValue: String?): String?
    {
        val value = rawValue?.trim()?.removeSuffix(";")?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun validatePropertyName(name: String)
    {
        require(Regex("^[A-Za-z0-9,._+#?-]+$").matches(name)) {
            "属性名包含不支持的字符：$name"
        }
    }
}
