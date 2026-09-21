package io.mo.dtbooverclocker.core.devicetree

enum class DeviceTreeReferenceKind
{
    LABEL,
    PATH,
    NUMERIC_CANDIDATE
}

data class DeviceTreeReference(
    val sourceNodePath: String,
    val propertyName: String,
    val token: String,
    val targetNodePath: String?,
    val kind: DeviceTreeReferenceKind
)
{
    val resolved: Boolean
        get() = targetNodePath != null
}

data class DeviceTreePhandleIdentity(
    val nodePath: String,
    val value: Long
)

data class DeviceTreeReferenceIndex(
    val labels: Map<String, String>,
    val phandles: Map<Long, String>,
    val references: List<DeviceTreeReference>
)
{
    fun outgoing(nodePath: String): List<DeviceTreeReference>
    {
        return references.filter { it.sourceNodePath == nodePath }
    }

    fun incoming(nodePath: String): List<DeviceTreeReference>
    {
        return references.filter { it.targetNodePath == nodePath }
    }

    fun unresolved(): List<DeviceTreeReference>
    {
        return references.filterNot { it.resolved }
    }
}

object DeviceTreeReferenceIndexer
{
    private val labelReferenceRegex = Regex("""&([A-Za-z_][A-Za-z0-9_.-]*)""")
    private val pathReferenceRegex = Regex("""&\{([^}]+)}""")

    fun build(document: DeviceTreeDocument): DeviceTreeReferenceIndex
    {
        val nodes = document.flatten()
        val directLabels = nodes
            .mapNotNull { node -> node.label?.let { it to node.path } }
            .toMap()

        val symbolLabels = parseSymbols(document)
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
        nodes.forEach { node ->
            node.properties.forEach { property ->
                val raw = property.rawValue ?: return@forEach

                pathReferenceRegex.findAll(raw).forEach { match ->
                    val path = normalizePath(match.groupValues[1])
                    references += DeviceTreeReference(
                        sourceNodePath = node.path,
                        propertyName = property.name,
                        token = match.value,
                        targetNodePath = document.findNode(path)?.path,
                        kind = DeviceTreeReferenceKind.PATH
                    )
                }

                val rawWithoutPathReferences = pathReferenceRegex.replace(raw, "")
                labelReferenceRegex.findAll(rawWithoutPathReferences).forEach { match ->
                    val label = match.groupValues[1]
                    references += DeviceTreeReference(
                        sourceNodePath = node.path,
                        propertyName = property.name,
                        token = match.value,
                        targetNodePath = labels[label],
                        kind = DeviceTreeReferenceKind.LABEL
                    )
                }

                if (property.name != "phandle" &&
                    property.name != "linux,phandle" &&
                    !raw.contains('&'))
                {
                    parseSingleCell(raw)?.let { value ->
                        phandles[value]?.let { targetPath ->
                            references += DeviceTreeReference(
                                sourceNodePath = node.path,
                                propertyName = property.name,
                                token = formatPhandle(value),
                                targetNodePath = targetPath,
                                kind = DeviceTreeReferenceKind.NUMERIC_CANDIDATE
                            )
                        }
                    }
                }
            }
        }

        return DeviceTreeReferenceIndex(
            labels = labels.toSortedMap(),
            phandles = phandles.toSortedMap(),
            references = references.distinct()
        )
    }

    private fun parseSymbols(document: DeviceTreeDocument): Map<String, String>
    {
        val symbols = document.findNode("/__symbols__") ?: return emptyMap()
        return buildMap {
            symbols.properties.forEach { property ->
                val path = property.rawValue
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.let(::normalizePath)
                    ?: return@forEach

                if (document.findNode(path) != null)
                {
                    put(property.name, path)
                }
            }
        }
    }

    private fun parseSingleCell(rawValue: String?): Long?
    {
        val raw = rawValue?.trim() ?: return null
        if (!raw.startsWith('<') || !raw.endsWith('>') || '&' in raw)
        {
            return null
        }

        val tokens = raw
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        if (tokens.size != 1)
        {
            return null
        }

        val token = tokens.single().trim().trimEnd(',')
        return if (token.startsWith("0x", ignoreCase = true))
        {
            token.substring(2).toLongOrNull(16)
        }
        else
        {
            token.toLongOrNull()
        }
    }

    private fun normalizePath(path: String): String
    {
        val trimmed = path.trim()
        return if (trimmed.startsWith('/'))
        {
            trimmed
        }
        else
        {
            "/$trimmed"
        }
    }

    private fun formatPhandle(value: Long): String
    {
        return "0x" + value.toString(16)
    }
}
