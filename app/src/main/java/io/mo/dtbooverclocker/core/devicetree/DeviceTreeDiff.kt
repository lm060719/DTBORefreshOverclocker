package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeDiff
{
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
            }
            is DeleteNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}  ${change.nodePath}")
                append("- 删除节点及其整个子树")
            }
            is RenameNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}")
                appendLine("- ${change.nodePath}")
                append("+ ${change.newNodePath}")
            }
            is CloneNodeChange -> buildString {
                appendLine("Entry ${change.entryIndex}  ${change.nodePath}")
                append("+ 克隆自 ${change.sourceNodePath}")
            }
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
