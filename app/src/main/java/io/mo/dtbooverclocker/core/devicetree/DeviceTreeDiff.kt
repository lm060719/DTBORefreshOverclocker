package io.mo.dtbooverclocker.core.devicetree

object DeviceTreeDiff
{
    fun render(change: DeviceTreeChange): String
    {
        val path = "${change.nodePath.trimEnd('/')}/${change.propertyName}"
        return when (change)
        {
            is SetPropertyChange -> buildString {
                appendLine("Entry ${change.entryIndex}  $path")
                appendLine("- ${statement(change.propertyName, change.oldRawValue)}")
                append("+ ${statement(change.propertyName, change.newRawValue)}")
            }
            is AddPropertyChange -> buildString {
                appendLine("Entry ${change.entryIndex}  $path")
                append("+ ${statement(change.propertyName, change.newRawValue)}")
            }
            is DeletePropertyChange -> buildString {
                appendLine("Entry ${change.entryIndex}  $path")
                append("- ${statement(change.propertyName, change.oldRawValue)}")
            }
        }
    }

    private fun statement(name: String, rawValue: String?): String
    {
        return if (rawValue.isNullOrBlank()) "$name;" else "$name = $rawValue;"
    }
}
