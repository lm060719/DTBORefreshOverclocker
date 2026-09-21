package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeEditor
{
    private val nodeNameRegex = Regex("^[A-Za-z0-9,._@+#-]+$")

    fun apply(text: String, change: DeviceTreeChange): String
    {
        return when (change)
        {
            is SetPropertyChange ->
            {
                val node = requireNode(change.entryIndex, text, change.nodePath)
                setProperty(text, node, change)
            }
            is AddPropertyChange ->
            {
                val node = requireNode(change.entryIndex, text, change.nodePath)
                addProperty(text, node, change)
            }
            is DeletePropertyChange ->
            {
                val node = requireNode(change.entryIndex, text, change.nodePath)
                deleteProperty(text, node, change)
            }
            is AddNodeChange -> addNode(text, change)
            is DeleteNodeChange -> deleteNode(text, change)
            is RenameNodeChange -> renameNode(text, change)
            is CloneNodeChange -> cloneNode(text, change)
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
        val node = requireNode(entryIndex, text, nodePath)
        require(node.properties.none { it.name == propertyName }) {
            "属性已存在：$nodePath/$propertyName"
        }
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

    fun buildAddNodeChange(
        entryIndex: Int,
        text: String,
        parentNodePath: String,
        nodeName: String
    ): AddNodeChange
    {
        validateNodeName(nodeName)
        val document = DeviceTreeParser.parse(entryIndex, text)
        val parent = requireNotNull(document.findNode(parentNodePath)) {
            "父节点不存在：$parentNodePath"
        }
        val targetPath = childPath(parentNodePath, nodeName)
        require(document.findNode(targetPath) == null) {
            "目标节点已存在：$targetPath"
        }

        val indent = childIndent(parent)
        val nodeSource = "$indent$nodeName {\n$indent};"
        return AddNodeChange(
            entryIndex = entryIndex,
            nodePath = targetPath,
            nodeSource = nodeSource
        )
    }

    fun buildCloneNodeChange(
        entryIndex: Int,
        text: String,
        sourceNodePath: String,
        newNodeName: String
    ): CloneNodeChange
    {
        require(sourceNodePath != "/") {
            "根节点不能被克隆"
        }
        validateNodeName(newNodeName)

        val document = DeviceTreeParser.parse(entryIndex, text)
        val source = requireNotNull(document.findNode(sourceNodePath)) {
            "源节点不存在：$sourceNodePath"
        }
        require(!containsLabel(source)) {
            "当前阶段不能克隆包含 label 的节点或子树，避免产生重复 label / phandle。"
        }

        val parentPath = parentPathOf(sourceNodePath)
        val targetPath = childPath(parentPath, newNodeName)
        require(document.findNode(targetPath) == null) {
            "目标节点已存在：$targetPath"
        }

        val rawSource = extractNodeSource(text, source)
        val renamed = renameRootNodeSource(rawSource, source.name, newNodeName)
        return CloneNodeChange(
            entryIndex = entryIndex,
            sourceNodePath = sourceNodePath,
            nodePath = targetPath,
            clonedNodeSource = renamed
        )
    }

    fun buildRenameNodeChange(
        entryIndex: Int,
        text: String,
        nodePath: String,
        newNodeName: String
    ): RenameNodeChange
    {
        require(nodePath != "/") {
            "根节点不能重命名"
        }
        validateNodeName(newNodeName)

        val document = DeviceTreeParser.parse(entryIndex, text)
        val node = requireNotNull(document.findNode(nodePath)) {
            "节点不存在：$nodePath"
        }
        require(node.name != newNodeName) {
            "新节点名与原节点名相同"
        }

        val targetPath = childPath(parentPathOf(nodePath), newNodeName)
        require(document.findNode(targetPath) == null) {
            "目标节点已存在：$targetPath"
        }

        return RenameNodeChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            oldName = node.name,
            newName = newNodeName,
            newNodePath = targetPath
        )
    }

    fun buildDeleteNodeChange(
        entryIndex: Int,
        text: String,
        nodePath: String
    ): DeleteNodeChange
    {
        require(nodePath != "/") {
            "根节点不能删除"
        }

        val node = requireNode(entryIndex, text, nodePath)
        return DeleteNodeChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            oldNodeSource = extractNodeSource(text, node)
        )
    }

    private fun setProperty(
        text: String,
        node: DeviceTreeNode,
        change: SetPropertyChange
    ): String
    {
        val property = requireNotNull(node.properties.firstOrNull { it.name == change.propertyName }) {
            "属性不存在：${change.nodePath}/${change.propertyName}"
        }
        val replacement = statement(property.indent, change.propertyName, change.newRawValue)
        return text.replaceRange(property.startOffset, property.endOffsetExclusive, replacement)
    }

    private fun addProperty(
        text: String,
        node: DeviceTreeNode,
        change: AddPropertyChange
    ): String
    {
        require(node.properties.none { it.name == change.propertyName }) {
            "属性已存在：${change.nodePath}/${change.propertyName}"
        }
        validatePropertyName(change.propertyName)
        val indent = childIndent(node)
        val insertion = statement(indent, change.propertyName, change.newRawValue) + "\n"
        return text.replaceRange(node.closeStartOffset, node.closeStartOffset, insertion)
    }

    private fun deleteProperty(
        text: String,
        node: DeviceTreeNode,
        change: DeletePropertyChange
    ): String
    {
        val property = requireNotNull(node.properties.firstOrNull { it.name == change.propertyName }) {
            "属性不存在：${change.nodePath}/${change.propertyName}"
        }
        return removeSourceRangeWithLineBreak(text, property.startOffset, property.endOffsetExclusive)
    }

    private fun addNode(text: String, change: AddNodeChange): String
    {
        val document = DeviceTreeParser.parse(change.entryIndex, text)
        require(document.findNode(change.nodePath) == null) {
            "目标节点已存在：${change.nodePath}"
        }

        val parentPath = parentPathOf(change.nodePath)
        val parent = requireNotNull(document.findNode(parentPath)) {
            "父节点不存在：$parentPath"
        }
        val source = reindentNodeSource(change.nodeSource, childIndent(parent))
        return insertNodeSource(text, parent, source)
    }

    private fun cloneNode(text: String, change: CloneNodeChange): String
    {
        val document = DeviceTreeParser.parse(change.entryIndex, text)
        requireNotNull(document.findNode(change.sourceNodePath)) {
            "源节点不存在：${change.sourceNodePath}"
        }
        require(document.findNode(change.nodePath) == null) {
            "目标节点已存在：${change.nodePath}"
        }

        val parentPath = parentPathOf(change.nodePath)
        val parent = requireNotNull(document.findNode(parentPath)) {
            "父节点不存在：$parentPath"
        }
        val source = reindentNodeSource(change.clonedNodeSource, childIndent(parent))
        return insertNodeSource(text, parent, source)
    }

    private fun deleteNode(text: String, change: DeleteNodeChange): String
    {
        require(change.nodePath != "/") {
            "根节点不能删除"
        }
        val node = requireNode(change.entryIndex, text, change.nodePath)
        return removeSourceRangeWithLineBreak(text, node.startOffset, node.endOffsetExclusive)
    }

    private fun renameNode(text: String, change: RenameNodeChange): String
    {
        require(change.nodePath != "/") {
            "根节点不能重命名"
        }

        val document = DeviceTreeParser.parse(change.entryIndex, text)
        val node = requireNotNull(document.findNode(change.nodePath)) {
            "节点不存在：${change.nodePath}"
        }
        require(node.name == change.oldName) {
            "节点名已变化，无法应用重命名：期望 ${change.oldName}，实际 ${node.name}"
        }
        require(document.findNode(change.newNodePath) == null) {
            "目标节点已存在：${change.newNodePath}"
        }

        val headerEnd = text.indexOf('{', node.startOffset)
        require(headerEnd >= node.startOffset) {
            "无法定位节点头：${change.nodePath}"
        }
        val header = text.substring(node.startOffset, headerEnd)
        val nameIndex = header.lastIndexOf(change.oldName)
        require(nameIndex >= 0) {
            "无法在节点头中定位名称：${change.oldName}"
        }

        val absoluteStart = node.startOffset + nameIndex
        return text.replaceRange(
            absoluteStart,
            absoluteStart + change.oldName.length,
            change.newName
        )
    }

    private fun requireNode(
        entryIndex: Int,
        text: String,
        nodePath: String
    ): DeviceTreeNode
    {
        val document = DeviceTreeParser.parse(entryIndex, text)
        return requireNotNull(document.findNode(nodePath)) {
            "设备树节点不存在：$nodePath"
        }
    }

    private fun requireProperty(
        entryIndex: Int,
        text: String,
        nodePath: String,
        propertyName: String
    ): DeviceTreeProperty
    {
        val node = requireNode(entryIndex, text, nodePath)
        return requireNotNull(node.properties.firstOrNull { it.name == propertyName }) {
            "属性不存在：$nodePath/$propertyName"
        }
    }

    private fun insertNodeSource(
        text: String,
        parent: DeviceTreeNode,
        nodeSource: String
    ): String
    {
        val insertion = nodeSource.trimEnd() + "\n"
        return text.replaceRange(
            parent.closeStartOffset,
            parent.closeStartOffset,
            insertion
        )
    }

    private fun extractNodeSource(
        text: String,
        node: DeviceTreeNode
    ): String
    {
        return text.substring(node.startOffset, node.endOffsetExclusive)
    }

    private fun reindentNodeSource(
        source: String,
        targetIndent: String
    ): String
    {
        val lines = source.lines()
        val firstIndent = lines.firstOrNull()
            ?.takeWhile(Char::isWhitespace)
            .orEmpty()

        return lines.joinToString("\n") { line ->
            when
            {
                line.isEmpty() -> line
                firstIndent.isEmpty() -> targetIndent + line
                line.startsWith(firstIndent) -> targetIndent + line.removePrefix(firstIndent)
                else -> line
            }
        }
    }

    private fun renameRootNodeSource(
        source: String,
        oldName: String,
        newName: String
    ): String
    {
        val headerEnd = source.indexOf('{')
        require(headerEnd >= 0) {
            "无法定位克隆节点头"
        }
        val header = source.substring(0, headerEnd)
        val index = header.lastIndexOf(oldName)
        require(index >= 0) {
            "无法在克隆节点头中定位名称：$oldName"
        }
        return source.replaceRange(index, index + oldName.length, newName)
    }

    private fun removeSourceRangeWithLineBreak(
        text: String,
        start: Int,
        endExclusive: Int
    ): String
    {
        var end = endExclusive
        if (end < text.length && text[end] == '\r')
        {
            end++
        }
        if (end < text.length && text[end] == '\n')
        {
            end++
        }
        return text.removeRange(start, end)
    }

    private fun childIndent(node: DeviceTreeNode): String
    {
        return node.properties.firstOrNull()?.indent
            ?: node.children.firstOrNull()?.indent
            ?: (node.indent + "\t")
    }

    private fun parentPathOf(path: String): String
    {
        require(path.startsWith("/") && path != "/") {
            "无效节点路径：$path"
        }
        val parent = path.substringBeforeLast('/')
        return if (parent.isEmpty())
        {
            "/"
        }
        else
        {
            parent
        }
    }

    private fun childPath(parentPath: String, childName: String): String
    {
        return if (parentPath == "/")
        {
            "/$childName"
        }
        else
        {
            "${parentPath.trimEnd('/')}/$childName"
        }
    }

    private fun containsLabel(node: DeviceTreeNode): Boolean
    {
        return node.label != null || node.children.any(::containsLabel)
    }

    private fun statement(
        indent: String,
        propertyName: String,
        rawValue: String?
    ): String
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

    private fun validateNodeName(name: String)
    {
        require(nodeNameRegex.matches(name) && name != "/") {
            "节点名包含不支持的字符：$name"
        }
    }
}
