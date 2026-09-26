package io.mo.dtbooverclocker.core.devicetree

enum class PropertyType
{
    STRING,
    STRING_LIST,
    U32,
    U64,
    CELLS,
    BYTE_ARRAY,
    BOOLEAN,
    PHANDLE,
    UNKNOWN
}

data class DeviceTreeProperty(
    val name: String,
    val type: PropertyType,
    val rawValue: String?,
    val rawStatement: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val indent: String
)
{
    val displayValue: String
        get() = when (type)
        {
            PropertyType.BOOLEAN -> "true"
            PropertyType.STRING -> rawValue?.trim()?.removeSurrounding("\"").orEmpty()
            PropertyType.STRING_LIST -> parseQuotedStrings(rawValue.orEmpty()).joinToString(", ")
            PropertyType.U32 -> parseCells(rawValue.orEmpty()).firstOrNull()?.toString().orEmpty()
            PropertyType.U64 ->
            {
                val cells = parseCells(rawValue.orEmpty())
                if (cells.size >= 2)
                {
                    ((cells[0] shl 32) or (cells[1] and 0xffffffffL)).toString()
                }
                else
                {
                    rawValue.orEmpty()
                }
            }
            else -> rawValue.orEmpty()
        }

    companion object
    {
        private val quotedStringRegex = Regex("\\\"((?:\\\\.|[^\\\"])*)\\\"")
        private val whitespaceRegex = Regex("\\s+")

        private fun parseQuotedStrings(raw: String): List<String>
        {
            return quotedStringRegex
                .findAll(raw)
                .map { it.groupValues[1] }
                .toList()
        }

        private fun parseCells(raw: String): List<Long>
        {
            val body = raw.trim().removePrefix("<").removeSuffix(">")
            return body.split(whitespaceRegex)
                .mapNotNull { token ->
                    val clean = token.trim().trimEnd(',')
                    when
                    {
                        clean.startsWith("0x", ignoreCase = true) -> clean.substring(2).toLongOrNull(16)
                        else -> clean.toLongOrNull()
                    }
                }
        }
    }
}

data class DeviceTreeNode(
    val name: String,
    val path: String,
    val label: String?,
    val properties: List<DeviceTreeProperty>,
    val children: List<DeviceTreeNode>,
    val startOffset: Int,
    val closeStartOffset: Int,
    val endOffsetExclusive: Int,
    val indent: String,
    val labels: List<String> = listOfNotNull(label)
)
{
    val propertyCount: Int
        get() = properties.size

    val childCount: Int
        get() = children.size
}

/** A source line the parser could not model; dtc may still compile it into the DTB. */
data class DeviceTreeParseWarning(
    val lineNumber: Int,
    val statement: String,
    val reason: String
)

data class DeviceTreeDocument(
    val entryIndex: Int,
    val root: DeviceTreeNode,
    val sourceText: String,
    val warnings: List<DeviceTreeParseWarning> = emptyList()
)
{
    /**
     * 设备树文档在能力扫描期间会被充电等分析器反复访问。
     * 旧实现的 findNode() 每次都会从根节点 DFS 整棵树，OPlus/Qualcomm overlay
     * 中大量 __fixups__ 会把复杂度放大到近似 O(N²)。这里一次性建立只读索引。
     */
    private val flattenedNodes: List<DeviceTreeNode> by lazy(LazyThreadSafetyMode.PUBLICATION)
    {
        buildList {
            fun walk(node: DeviceTreeNode)
            {
                add(node)
                node.children.forEach(::walk)
            }
            walk(root)
        }
    }

    private val nodeByPath: Map<String, DeviceTreeNode> by lazy(LazyThreadSafetyMode.PUBLICATION)
    {
        flattenedNodes.associateBy(DeviceTreeNode::path)
    }

    fun findNode(path: String): DeviceTreeNode? = nodeByPath[path]

    fun flatten(): List<DeviceTreeNode> = flattenedNodes
}
