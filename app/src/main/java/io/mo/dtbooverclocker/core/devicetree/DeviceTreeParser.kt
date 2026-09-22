package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeParser
{
    private data class MutableNode(
        val name: String,
        val path: String,
        val label: String?,
        val startOffset: Int,
        val indent: String,
        val properties: MutableList<DeviceTreeProperty> = mutableListOf(),
        val children: MutableList<DeviceTreeNode> = mutableListOf()
    )

    private val nodeStartRegex = Regex(
        """^(\s*)(?:(?<label>[A-Za-z_][A-Za-z0-9_.-]*):\s*)?(?<name>[/A-Za-z0-9,._@+#-]+)\s*\{\s*$"""
    )

    private val propertyNameRegex = Regex("""^[A-Za-z0-9,._+#?-]+$""")

    fun parse(entryIndex: Int, text: String, checkCancellation: () -> Unit = {}): DeviceTreeDocument
    {
        val stack = mutableListOf<MutableNode>()
        var root: DeviceTreeNode? = null
        var offset = 0
        var pendingStart = -1
        var pendingIndent = ""
        val pending = StringBuilder()

        Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(text).forEach { match ->
            checkCancellation()
            val line = match.value.trimEnd('\r', '\n')
            val lineWithBreakLength = match.value.length
            val trimmed = line.trim()

            if (pending.isNotEmpty())
            {
                pending.append('\n').append(line)
                if (trimmed.endsWith(';'))
                {
                    stack.lastOrNull()?.let { node ->
                        parseProperty(pending.toString(), pendingStart, offset + line.length, pendingIndent)?.let {
                            node.properties += it
                        }
                    }
                    pending.clear()
                    pendingStart = -1
                    pendingIndent = ""
                }
                offset += lineWithBreakLength
                return@forEach
            }

            val nodeMatch = nodeStartRegex.matchEntire(line)
            if (nodeMatch != null)
            {
                val name = nodeMatch.groups["name"]!!.value
                val label = nodeMatch.groups["label"]?.value
                val parentPath = stack.lastOrNull()?.path
                val path = when
                {
                    name == "/" -> "/"
                    parentPath == null || parentPath == "/" -> "/$name"
                    else -> "${parentPath.trimEnd('/')}/$name"
                }
                stack += MutableNode(
                    name = name,
                    path = path,
                    label = label,
                    startOffset = offset,
                    indent = nodeMatch.groupValues[1]
                )
                offset += lineWithBreakLength
                return@forEach
            }

            if (trimmed == "};" || trimmed == "}")
            {
                val mutable = stack.removeLastOrNull()
                if (mutable != null)
                {
                    val node = DeviceTreeNode(
                        name = mutable.name,
                        path = mutable.path,
                        label = mutable.label,
                        properties = mutable.properties.toList(),
                        children = mutable.children.toList(),
                        startOffset = mutable.startOffset,
                        closeStartOffset = offset,
                        endOffsetExclusive = offset + line.length,
                        indent = mutable.indent
                    )
                    if (stack.isEmpty())
                    {
                        root = node
                    }
                    else
                    {
                        stack.last().children += node
                    }
                }
                offset += lineWithBreakLength
                return@forEach
            }

            if (stack.isNotEmpty() && trimmed.isNotEmpty() && !trimmed.startsWith("/") && !trimmed.startsWith("//"))
            {
                if (trimmed.endsWith(';'))
                {
                    parseProperty(line, offset, offset + line.length, line.takeWhile(Char::isWhitespace))?.let {
                        stack.last().properties += it
                    }
                }
                else if ("=" in trimmed)
                {
                    pendingStart = offset
                    pendingIndent = line.takeWhile(Char::isWhitespace)
                    pending.append(line)
                }
            }

            offset += lineWithBreakLength
        }

        val parsedRoot = requireNotNull(root) { "DTS 中没有找到根节点" }
        return DeviceTreeDocument(entryIndex, parsedRoot, text)
    }

    private fun parseProperty(
        statement: String,
        startOffset: Int,
        endOffsetInclusive: Int,
        indent: String
    ): DeviceTreeProperty?
    {
        val trimmed = statement.trim()
        if (!trimmed.endsWith(';'))
        {
            return null
        }

        val withoutSemicolon = trimmed.dropLast(1).trim()
        val equalsIndex = withoutSemicolon.indexOf('=')
        val name: String
        val rawValue: String?

        if (equalsIndex >= 0)
        {
            name = withoutSemicolon.substring(0, equalsIndex).trim()
            rawValue = withoutSemicolon.substring(equalsIndex + 1).trim()
        }
        else
        {
            name = withoutSemicolon.trim()
            rawValue = null
        }

        if (!propertyNameRegex.matches(name))
        {
            return null
        }

        return DeviceTreeProperty(
            name = name,
            type = inferType(rawValue),
            rawValue = rawValue,
            rawStatement = trimmed,
            startOffset = startOffset,
            endOffsetExclusive = endOffsetInclusive,
            indent = indent
        )
    }

    private fun inferType(rawValue: String?): PropertyType
    {
        if (rawValue == null)
        {
            return PropertyType.BOOLEAN
        }

        val raw = rawValue.trim()
        if (raw.startsWith('[') && raw.endsWith(']'))
        {
            return PropertyType.BYTE_ARRAY
        }
        if (raw.startsWith('<') && raw.endsWith('>'))
        {
            if ('&' in raw)
            {
                return PropertyType.PHANDLE
            }

            val tokens = raw.removePrefix("<").removeSuffix(">").trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            return when (tokens.size)
            {
                1 -> PropertyType.U32
                else -> PropertyType.CELLS
            }
        }
        if (raw.startsWith('"'))
        {
            val count = Regex("\\\"(?:\\\\.|[^\\\"])*\\\"").findAll(raw).count()
            return if (count > 1) PropertyType.STRING_LIST else PropertyType.STRING
        }

        return PropertyType.UNKNOWN
    }
}
