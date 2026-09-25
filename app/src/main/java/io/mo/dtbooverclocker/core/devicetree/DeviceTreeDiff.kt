package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeDiff
{
    private const val PREVIEW_LINES = 8

    fun render(change: DeviceTreeChange): String
    {
        return when (change)
        {
            is SetPropertyChange ->
            {
                val path = propertyPath(change)
                buildString {
                    appendLine("Entry ${change.entryIndex}  $path")
                    appendLine("- ${statement(change.propertyName, change.oldRawValue)}")
                    append("+ ${statement(change.propertyName, change.newRawValue)}")
                }
            }
            is AddPropertyChange ->
            {
                val path = propertyPath(change)
                buildString {
                    appendLine("Entry ${change.entryIndex}  $path")
                    append("+ ${statement(change.propertyName, change.newRawValue)}")
                }
            }
            is DeletePropertyChange ->
            {
                val path = propertyPath(change)
                buildString {
                    appendLine("Entry ${change.entryIndex}  $path")
                    append("- ${statement(change.propertyName, change.oldRawValue)}")
                }
            }
            is AddNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}  ${change.nodePath}")
                append("+ 新增节点")
                appendSourcePreview('+', change.nodeSource)
            }
            is DeleteNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}  ${change.nodePath}")
                append("- 删除节点及其整个子树")
                appendSourcePreview('-', change.oldNodeSource)
            }
            is RenameNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}")
                appendLine("- ${change.nodePath}")
                append("+ ${change.newNodePath}")
            }
            is CloneNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}  ${change.nodePath}")
                append("+ 克隆自 ${change.sourceNodePath}")
                appendSourcePreview('+', change.clonedNodeSource)
            }
        }
    }

    /** Appends the first lines of a node's DTS source, de-indented, each prefixed with [marker]. */
    private fun StringBuilder.appendSourcePreview(marker: Char, source: String)
    {
        val lines = source.trimEnd().lines()
        val commonIndent = lines
            .filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile(Char::isWhitespace).length }
            ?: 0
        lines.take(PREVIEW_LINES).forEach { line ->
            appendLine()
            append(marker).append(' ').append(line.drop(minOf(commonIndent, line.takeWhile(Char::isWhitespace).length)))
        }
        if (lines.size > PREVIEW_LINES)
        {
            appendLine()
            append(marker).append(" … 共 ${lines.size} 行")
        }
    }

    private fun propertyPath(change: DeviceTreeChange): String
    {
        val name = requireNotNull(change.propertyName)
        return "${change.nodePath.trimEnd('/')}/$name"
    }

    private fun statement(name: String, rawValue: String?): String
    {
        return if (rawValue.isNullOrBlank())
        {
            "$name;"
        }
        else
        {
            "$name = $rawValue;"
        }
    }
}
