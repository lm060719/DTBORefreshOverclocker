package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeEditor
{
    private val nodeNameRegex = Regex("^[A-Za-z0-9,._@+#-]+$")
    private val propertyNameRegex = Regex("^[A-Za-z0-9,._+#?-]+$")

    fun apply(text: String, change: DeviceTreeChange): String
    {
        return apply(text, change, DeviceTreeParser.parse(change.entryIndex, text))
    }

    /** Applies [change] using a [document] the caller already parsed from exactly [text]. */
    fun apply(text: String, change: DeviceTreeChange, document: DeviceTreeDocument): String
    {
        require(document.sourceText == text) {
            "设备树文档与待修改文本不一致"
        }
        return when (change)
        {
            is SetPropertyChange -> setProperty(text, requireNode(document, change.nodePath), change)
            is AddPropertyChange -> addProperty(text, requireNode(document, change.nodePath), change)
            is DeletePropertyChange -> deleteProperty(text, requireNode(document, change.nodePath), change)
            is AddNodeChange -> addNode(text, document, change)
            is DeleteNodeChange -> deleteNode(text, document, change)
            is RenameNodeChange -> renameNode(text, document, change)
            is CloneNodeChange -> cloneNode(text, document, change)
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
        requireValidRawValue(newRawValue)
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
        requireValidRawValue(newRawValue)
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
        newNodeName: String,
        stripRootLabel: Boolean = false
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
        if (stripRootLabel)
        {
            require(!hasExplicitPhandle(source)) {
                "源节点包含 phandle 或 linux,phandle，不能通过剥离 Label 的方式安全克隆。"
            }
            require(source.children.none(::containsCloneIdentity)) {
                "源节点子树包含子节点 Label 或 phandle，不能安全克隆。"
            }
        }
        else
        {
            require(!containsCloneIdentity(source)) {
                "当前阶段不能克隆包含 label、phandle 或 linux,phandle 的节点子树，避免产生重复节点身份。"
            }
        }

        val parentPath = parentPathOf(sourceNodePath)
        val targetPath = childPath(parentPath, newNodeName)
        require(document.findNode(targetPath) == null) {
            "目标节点已存在：$targetPath"
        }

        val rawSource = extractNodeSource(text, source)
        val renamed = renameRootNodeSource(
            source = rawSource,
            oldName = source.name,
            newName = newNodeName,
            stripRootLabel = stripRootLabel
        )
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
        requireExpectedValue(change.nodePath, property, change.oldRawValue)
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
        // DTC requires properties before the first child node, including on undo.
        val insertionOffset = node.children.firstOrNull()?.startOffset ?: node.closeStartOffset
        return text.replaceRange(insertionOffset, insertionOffset, insertion)
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
        requireExpectedValue(change.nodePath, property, change.oldRawValue)
        return removeSourceRangeWithLineBreak(text, property.startOffset, property.endOffsetExclusive)
    }

    // Guards against clobbering a value that another transaction changed after this change was built.
    private fun requireExpectedValue(
        nodePath: String,
        property: DeviceTreeProperty,
        expectedRawValue: String?
    )
    {
        require(normalizeRawValue(property.rawValue) == normalizeRawValue(expectedRawValue)) {
            "属性值已变化，拒绝覆盖：$nodePath/${property.name}，" +
                "期望 ${expectedRawValue ?: "<boolean>"}，实际 ${property.rawValue ?: "<boolean>"}"
        }
    }

    private fun addNode(text: String, document: DeviceTreeDocument, change: AddNodeChange): String
    {
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

    private fun cloneNode(text: String, document: DeviceTreeDocument, change: CloneNodeChange): String
    {
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

    private fun deleteNode(text: String, document: DeviceTreeDocument, change: DeleteNodeChange): String
    {
        require(change.nodePath != "/") {
            "根节点不能删除"
        }
        val node = requireNode(document, change.nodePath)
        return removeSourceRangeWithLineBreak(text, node.startOffset, node.endOffsetExclusive)
    }

    private fun renameNode(text: String, document: DeviceTreeDocument, change: RenameNodeChange): String
    {
        require(change.nodePath != "/") {
            "根节点不能重命名"
        }

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
        return requireNode(DeviceTreeParser.parse(entryIndex, text), nodePath)
    }

    private fun requireNode(document: DeviceTreeDocument, nodePath: String): DeviceTreeNode
    {
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
        newName: String,
        stripRootLabel: Boolean
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

        if (!stripRootLabel)
        {
            return source.replaceRange(index, index + oldName.length, newName)
        }

        val prefix = header.substring(0, index)
        val indent = prefix.takeWhile(Char::isWhitespace)
        val suffix = source.substring(index + oldName.length)
        return indent + newName + suffix
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

    private fun containsCloneIdentity(node: DeviceTreeNode): Boolean
    {
        return node.label != null ||
            hasExplicitPhandle(node) ||
            node.children.any(::containsCloneIdentity)
    }

    private fun hasExplicitPhandle(node: DeviceTreeNode): Boolean
    {
        return node.properties.any {
            it.name == "phandle" || it.name == "linux,phandle"
        }
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

    /**
     * 检查用户输入的 Raw DTS 值能否作为单个属性值安全写入。
     *
     * 解析器按行识别节点与属性：值里一旦出现引号外的 `;`、`{`、`}` 或注释，
     * dtc 编译出的结构就会与编辑器看到的结构分叉（例如多出一个属性，或提前闭合节点）。
     * 返回 null 表示通过，否则返回错误说明。
     */
    fun rawValueError(rawValue: String?): String?
    {
        val value = normalizeRawValue(rawValue) ?: return null
        var index = 0
        var inCells = false
        var inBytes = false
        var parenDepth = 0

        while (index < value.length)
        {
            val char = value[index]
            val next = value.getOrNull(index + 1)
            when
            {
                char == '"' || (char == '\'' && inCells) ->
                {
                    if (char == '"' && (inCells || inBytes)) return "字符串不能写在 <> 或 [] 内部"
                    var end = index + 1
                    while (end < value.length && value[end] != char)
                    {
                        if (value[end] == '\n' || value[end] == '\r') return "引号内不能换行"
                        end += if (value[end] == '\\') 2 else 1
                    }
                    if (end >= value.length) return "字符串缺少结束引号"
                    index = end
                }
                char == '&' && next == '{' ->
                {
                    val end = value.indexOf('}', index + 2)
                    if (end < 0) return "路径引用 &{…} 缺少 '}'"
                    if (value.substring(index + 2, end).any { it in ";{\n\r" }) return "路径引用 &{…} 内容无效"
                    index = end
                }
                char == '/' && (next == '/' || next == '*') -> return "属性值中不支持注释"
                char == ';' -> return "引号外不能包含 ';'，一次只能编辑一个属性"
                char == '{' || char == '}' -> return "引号外不能包含 '{' 或 '}'"
                parenDepth > 0 && (char == '<' || char == '>') -> Unit
                char == '<' ->
                {
                    if (inCells || inBytes) return "'<' 不能嵌套"
                    inCells = true
                }
                char == '>' ->
                {
                    if (!inCells) return "多余的 '>'"
                    inCells = false
                }
                char == '(' ->
                {
                    if (!inCells) return "表达式括号只能出现在 <> 内部"
                    parenDepth++
                }
                char == ')' ->
                {
                    if (parenDepth == 0) return "多余的 ')'"
                    parenDepth--
                }
                char == '[' ->
                {
                    if (inCells || inBytes) return "'[' 不能嵌套"
                    inBytes = true
                }
                char == ']' ->
                {
                    if (!inBytes) return "多余的 ']'"
                    inBytes = false
                }
            }
            index++
        }

        return when
        {
            parenDepth > 0 -> "缺少 ')'"
            inCells -> "缺少 '>'"
            inBytes -> "缺少 ']'"
            else -> null
        }
    }

    private fun requireValidRawValue(rawValue: String?)
    {
        rawValueError(rawValue)?.let { error ->
            throw IllegalArgumentException("属性值语法无效：$error")
        }
    }

    private fun validatePropertyName(name: String)
    {
        require(propertyNameRegex.matches(name)) {
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
