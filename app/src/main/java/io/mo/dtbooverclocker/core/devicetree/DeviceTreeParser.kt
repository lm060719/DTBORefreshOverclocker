package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeParser
{
    private data class MutableNode(
        val name: String,
        val path: String,
        val labels: List<String>,
        val startOffset: Int,
        val indent: String,
        val properties: MutableList<DeviceTreeProperty> = mutableListOf(),
        val children: MutableList<DeviceTreeNode> = mutableListOf()
    )

    // dtc emits every label of a node on its header line, e.g. `timing_0_37: timing_0_146: timing@0 {`.
    private val nodeStartRegex = Regex(
        """^(\s*)(?<labels>(?:[A-Za-z_][A-Za-z0-9_.-]*:\s*)*)(?<name>[/A-Za-z0-9,._@+#-]+)\s*\{\s*$"""
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

        // DTS 往往是数 MB 的大文本。旧实现用正则 findAll() 拆行，会为每一行创建
        // MatchResult；在 OPlus/Qualcomm 大型 overlay 上能力扫描会被明显放大。
        // 这里直接按 CR/LF 单次线性遍历，同时保持原始字符偏移不变。
        while (offset < text.length)
        {
            checkCancellation()

            val lineStart = offset
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\r' && text[lineEnd] != '\n')
            {
                lineEnd++
            }

            var nextOffset = lineEnd
            if (nextOffset < text.length)
            {
                nextOffset = if (
                    text[nextOffset] == '\r' &&
                    nextOffset + 1 < text.length &&
                    text[nextOffset + 1] == '\n'
                )
                {
                    nextOffset + 2
                }
                else
                {
                    nextOffset + 1
                }
            }

            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trim()

            if (pending.isNotEmpty())
            {
                pending.append('\n').append(line)
                if (trimmed.endsWith(';'))
                {
                    stack.lastOrNull()?.let { node ->
                        parseProperty(pending.toString(), pendingStart, lineStart + line.length, pendingIndent)?.let {
                            node.properties += it
                        }
                    }
                    pending.clear()
                    pendingStart = -1
                    pendingIndent = ""
                }
                offset = nextOffset
                continue
            }

            val nodeMatch = nodeStartRegex.matchEntire(line)
            if (nodeMatch != null)
            {
                val name = nodeMatch.groups["name"]!!.value
                val labels = nodeMatch.groups["labels"]!!.value
                    .split(':')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
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
                    labels = labels,
                    startOffset = lineStart,
                    indent = nodeMatch.groupValues[1]
                )
                offset = nextOffset
                continue
            }

            if (trimmed == "};" || trimmed == "}")
            {
                val mutable = stack.removeLastOrNull()
                if (mutable != null)
                {
                    val node = DeviceTreeNode(
                        name = mutable.name,
                        path = mutable.path,
                        label = mutable.labels.firstOrNull(),
                        labels = mutable.labels,
                        properties = mutable.properties.toList(),
                        children = mutable.children.toList(),
                        startOffset = mutable.startOffset,
                        closeStartOffset = lineStart,
                        endOffsetExclusive = lineStart + line.length,
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
                offset = nextOffset
                continue
            }

            if (stack.isNotEmpty() && trimmed.isNotEmpty() && !trimmed.startsWith("/") && !trimmed.startsWith("//"))
            {
                if (trimmed.endsWith(';'))
                {
                    parseProperty(line, lineStart, lineStart + line.length, line.takeWhile(Char::isWhitespace))?.let {
                        stack.last().properties += it
                    }
                }
                else if ("=" in trimmed)
                {
                    pendingStart = lineStart
                    pendingIndent = line.takeWhile(Char::isWhitespace)
                    pending.append(line)
                }
            }

            offset = nextOffset
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
