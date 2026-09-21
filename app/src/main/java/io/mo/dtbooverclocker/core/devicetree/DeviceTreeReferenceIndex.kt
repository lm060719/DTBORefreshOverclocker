package io.mo.dtbooverclocker.core.devicetree

enum class DeviceTreeReferenceKind
{
    LABEL,
    PATH,
    LOCAL_FIXUP,
    EXTERNAL_FIXUP,
    NUMERIC_CANDIDATE
}

data class DeviceTreeReference(
    val sourceNodePath: String,
    val propertyName: String,
    val token: String,
    val targetNodePath: String?,
    val kind: DeviceTreeReferenceKind,
    val cellOffsetBytes: Int? = null
)
{
    val resolved: Boolean
        get() = targetNodePath != null
}

data class DeviceTreeReferenceIndex(
    val labels: Map<String, String>,
    val phandles: Map<Long, String>,
    val references: List<DeviceTreeReference>,
    private val outgoingByNode: Map<String, List<DeviceTreeReference>>,
    private val incomingByNode: Map<String, List<DeviceTreeReference>>
)
{
    fun outgoing(nodePath: String): List<DeviceTreeReference>
    {
        return outgoingByNode[nodePath].orEmpty()
    }

    fun incoming(nodePath: String): List<DeviceTreeReference>
    {
        return incomingByNode[nodePath].orEmpty()
    }

    fun unresolved(): List<DeviceTreeReference>
    {
        return references.filter {
            !it.resolved && it.kind != DeviceTreeReferenceKind.EXTERNAL_FIXUP
        }
    }

    fun externalFixups(): List<DeviceTreeReference>
    {
        return references.filter { it.kind == DeviceTreeReferenceKind.EXTERNAL_FIXUP }
    }
}

object DeviceTreeReferenceIndexer
{
    private val labelReferenceRegex = Regex("""&([A-Za-z_][A-Za-z0-9_.-]*)""")
    private val pathReferenceRegex = Regex("""&\{([^}]+)}""")
    private val quotedStringRegex = Regex("""\"((?:\\.|[^\"\\])*)\"""")

    fun build(document: DeviceTreeDocument): DeviceTreeReferenceIndex
    {
        val nodes = document.flatten()
        val nodesByPath = nodes.associateBy { it.path }

        val directLabels = nodes
            .mapNotNull { node -> node.label?.let { it to node.path } }
            .toMap()

        val symbolLabels = parseSymbols(document, nodesByPath)
        val labels = symbolLabels + directLabels

        val phandles = buildMap<Long, String> {
            nodes.forEach { node ->
                node.properties
                    .filter { it.name == "phandle" || it.name == "linux,phandle" }
                    .mapNotNull { parseSingleCell(it.rawValue) }
                    .forEach { value -> put(value, node.path) }
            }
        }

        val references = mutableListOf<DeviceTreeReference>()

        appendSymbolicReferences(
            nodes = nodes,
            labels = labels,
            nodesByPath = nodesByPath,
            output = references
        )

        appendLocalFixupReferences(
            document = document,
            nodesByPath = nodesByPath,
            phandles = phandles,
            output = references
        )

        appendExternalFixupReferences(
            document = document,
            nodesByPath = nodesByPath,
            output = references
        )

        val uniqueReferences = references.distinct()

        return DeviceTreeReferenceIndex(
            labels = labels.toSortedMap(),
            phandles = phandles.toSortedMap(),
            references = uniqueReferences,
            outgoingByNode = uniqueReferences.groupBy { it.sourceNodePath },
            incomingByNode = uniqueReferences
                .filter { it.targetNodePath != null }
                .groupBy { requireNotNull(it.targetNodePath) }
        )
    }

    private fun appendSymbolicReferences(
        nodes: List<DeviceTreeNode>,
        labels: Map<String, String>,
        nodesByPath: Map<String, DeviceTreeNode>,
        output: MutableList<DeviceTreeReference>
    )
    {
        nodes.asSequence()
            .filterNot { isMetadataNode(it.path) }
            .forEach { node ->
                node.properties.forEach propertyLoop@ { property ->
                    val raw = property.rawValue ?: return@propertyLoop

                    pathReferenceRegex.findAll(raw).forEach { match ->
                        val path = normalizePath(match.groupValues[1])

                        output += DeviceTreeReference(
                            sourceNodePath = node.path,
                            propertyName = property.name,
                            token = match.value,
                            targetNodePath = nodesByPath[path]?.path,
                            kind = DeviceTreeReferenceKind.PATH
                        )
                    }

                    val rawWithoutPathReferences = pathReferenceRegex.replace(raw, "")

                    labelReferenceRegex.findAll(rawWithoutPathReferences).forEach { match ->
                        val label = match.groupValues[1]

                        output += DeviceTreeReference(
                            sourceNodePath = node.path,
                            propertyName = property.name,
                            token = match.value,
                            targetNodePath = labels[label],
                            kind = DeviceTreeReferenceKind.LABEL
                        )
                    }
                }
            }
    }

    private fun appendLocalFixupReferences(
        document: DeviceTreeDocument,
        nodesByPath: Map<String, DeviceTreeNode>,
        phandles: Map<Long, String>,
        output: MutableList<DeviceTreeReference>
    )
    {
        val localFixups = document.findNode("/__local_fixups__") ?: return

        flatten(localFixups).forEach { fixupNode ->
            if (fixupNode.path == "/__local_fixups__")
            {
                return@forEach
            }

            val sourcePath = normalizePath(
                fixupNode.path.removePrefix("/__local_fixups__")
            )

            val sourceNode = nodesByPath[sourcePath] ?: return@forEach

            fixupNode.properties.forEach propertyLoop@ { fixupProperty ->
                val sourceProperty = sourceNode.properties
                    .firstOrNull { it.name == fixupProperty.name }
                    ?: return@propertyLoop

                val sourceCells = parseCells(sourceProperty.rawValue)

                if (sourceCells.isEmpty())
                {
                    return@propertyLoop
                }

                parseCells(fixupProperty.rawValue).forEach offsetLoop@ { offsetValue ->
                    if (offsetValue > Int.MAX_VALUE || offsetValue % 4L != 0L)
                    {
                        return@offsetLoop
                    }

                    val offset = offsetValue.toInt()
                    val cellIndex = offset / 4
                    val targetPhandle = sourceCells.getOrNull(cellIndex)
                        ?: return@offsetLoop

                    output += DeviceTreeReference(
                        sourceNodePath = sourcePath,
                        propertyName = fixupProperty.name,
                        token = formatPhandle(targetPhandle),
                        targetNodePath = phandles[targetPhandle],
                        kind = DeviceTreeReferenceKind.LOCAL_FIXUP,
                        cellOffsetBytes = offset
                    )
                }
            }
        }
    }

    private fun appendExternalFixupReferences(
        document: DeviceTreeDocument,
        nodesByPath: Map<String, DeviceTreeNode>,
        output: MutableList<DeviceTreeReference>
    )
    {
        val fixups = document.findNode("/__fixups__") ?: return

        fixups.properties.forEach { fixupProperty ->
            parseQuotedStrings(fixupProperty.rawValue.orEmpty())
                .forEach descriptorLoop@ { descriptor ->
                    val parsed = parseFixupDescriptor(descriptor)
                        ?: return@descriptorLoop

                    if (nodesByPath[parsed.nodePath] == null)
                    {
                        return@descriptorLoop
                    }

                    output += DeviceTreeReference(
                        sourceNodePath = parsed.nodePath,
                        propertyName = parsed.propertyName,
                        token = "&${fixupProperty.name}",
                        targetNodePath = null,
                        kind = DeviceTreeReferenceKind.EXTERNAL_FIXUP,
                        cellOffsetBytes = parsed.offsetBytes
                    )
                }
        }
    }

    private fun parseSymbols(
        document: DeviceTreeDocument,
        nodesByPath: Map<String, DeviceTreeNode>
    ): Map<String, String>
    {
        val symbols = document.findNode("/__symbols__") ?: return emptyMap()

        return buildMap {
            symbols.properties.forEach { property ->
                val path = parseQuotedStrings(property.rawValue.orEmpty())
                    .firstOrNull()
                    ?.let(::normalizePath)
                    ?: return@forEach

                if (nodesByPath[path] != null)
                {
                    put(property.name, path)
                }
            }
        }
    }

    private data class FixupDescriptor(
        val nodePath: String,
        val propertyName: String,
        val offsetBytes: Int
    )

    private fun parseFixupDescriptor(value: String): FixupDescriptor?
    {
        val offsetSeparator = value.lastIndexOf(':')

        if (offsetSeparator <= 0)
        {
            return null
        }

        val propertySeparator = value.lastIndexOf(':', offsetSeparator - 1)

        if (propertySeparator <= 0)
        {
            return null
        }

        val path = normalizePath(value.substring(0, propertySeparator))
        val property = value.substring(propertySeparator + 1, offsetSeparator)
        val offsetText = value.substring(offsetSeparator + 1)
        val offset = parseUnsignedNumber(offsetText)?.let {
            if (it in 0..Int.MAX_VALUE.toLong()) it.toInt() else null
        } ?: return null

        return FixupDescriptor(
            nodePath = path,
            propertyName = property,
            offsetBytes = offset
        )
    }

    private fun parseQuotedStrings(raw: String): List<String>
    {
        return quotedStringRegex.findAll(raw)
            .map { unescapeString(it.groupValues[1]) }
            .toList()
    }

    private fun unescapeString(value: String): String
    {
        val output = StringBuilder(value.length)
        var index = 0

        while (index < value.length)
        {
            val char = value[index]

            if (char == '\\' && index + 1 < value.length)
            {
                output.append(value[index + 1])
                index += 2
            }
            else
            {
                output.append(char)
                index++
            }
        }

        return output.toString()
    }

    private fun parseSingleCell(rawValue: String?): Long?
    {
        return parseCells(rawValue).singleOrNull()
    }

    private fun parseCells(rawValue: String?): List<Long>
    {
        val raw = rawValue?.trim() ?: return emptyList()

        if (!raw.startsWith('<') || !raw.endsWith('>') || '&' in raw)
        {
            return emptyList()
        }

        return raw
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .mapNotNull(::parseUnsignedNumber)
    }

    private fun parseUnsignedNumber(token: String): Long?
    {
        val clean = token.trim().trimEnd(',')

        if (clean.startsWith("-"))
        {
            return null
        }

        return if (clean.startsWith("0x", ignoreCase = true))
        {
            clean.substring(2).toLongOrNull(16)
        }
        else
        {
            clean.toLongOrNull()
        }
    }

    private fun flatten(root: DeviceTreeNode): List<DeviceTreeNode>
    {
        val result = mutableListOf<DeviceTreeNode>()

        fun walk(node: DeviceTreeNode)
        {
            result += node
            node.children.forEach(::walk)
        }

        walk(root)
        return result
    }

    private fun isMetadataNode(path: String): Boolean
    {
        return path == "/__symbols__" ||
            path == "/__fixups__" ||
            path == "/__local_fixups__" ||
            path.startsWith("/__local_fixups__/")
    }

    private fun normalizePath(path: String): String
    {
        val trimmed = path.trim()

        return when
        {
            trimmed.isEmpty() -> "/"
            trimmed.startsWith('/') -> trimmed
            else -> "/$trimmed"
        }
    }

    private fun formatPhandle(value: Long): String
    {
        return "0x" + value.toString(16)
    }
}
