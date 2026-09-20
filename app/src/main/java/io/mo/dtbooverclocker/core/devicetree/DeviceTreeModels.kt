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
        private fun parseQuotedStrings(raw: String): List<String>
        {
            return Regex("\\\"((?:\\\\.|[^\\\"])*)\\\"")
                .findAll(raw)
                .map { it.groupValues[1] }
                .toList()
        }

        private fun parseCells(raw: String): List<Long>
        {
            val body = raw.trim().removePrefix("<").removeSuffix(">")
            return body.split(Regex("\\s+"))
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
    val indent: String
)
{
    val propertyCount: Int
        get() = properties.size

    val childCount: Int
        get() = children.size
}

data class DeviceTreeDocument(
    val entryIndex: Int,
    val root: DeviceTreeNode,
    val sourceText: String
)
{
    fun findNode(path: String): DeviceTreeNode?
    {
        fun walk(node: DeviceTreeNode): DeviceTreeNode?
        {
            if (node.path == path)
            {
                return node
            }

            node.children.forEach { child ->
                walk(child)?.let { return it }
            }
            return null
        }

        return walk(root)
    }

    fun flatten(): List<DeviceTreeNode>
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
}
