package io.mo.dtbooverclocker.core.devicetree

import java.util.UUID

sealed interface DeviceTreeChange
{
    val id: String
    val entryIndex: Int
    val nodePath: String
    val propertyName: String?
    val timestamp: Long
    val summary: String

    fun inverse(): DeviceTreeChange
}

data class SetPropertyChange(
    override val entryIndex: Int,
    override val nodePath: String,
    override val propertyName: String,
    val oldRawValue: String?,
    val newRawValue: String?,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val summary: String
        get() = "修改 $nodePath/$propertyName: ${format(oldRawValue)} → ${format(newRawValue)}"

    override fun inverse(): DeviceTreeChange
    {
        return SetPropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = propertyName,
            oldRawValue = newRawValue,
            newRawValue = oldRawValue
        )
    }
}

data class AddPropertyChange(
    override val entryIndex: Int,
    override val nodePath: String,
    override val propertyName: String,
    val newRawValue: String?,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val summary: String
        get() = "新增 $nodePath/$propertyName = ${format(newRawValue)}"

    override fun inverse(): DeviceTreeChange
    {
        return DeletePropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = propertyName,
            oldRawValue = newRawValue
        )
    }
}

data class DeletePropertyChange(
    override val entryIndex: Int,
    override val nodePath: String,
    override val propertyName: String,
    val oldRawValue: String?,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val summary: String
        get() = "删除 $nodePath/$propertyName (原值 ${format(oldRawValue)})"

    override fun inverse(): DeviceTreeChange
    {
        return AddPropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = propertyName,
            newRawValue = oldRawValue
        )
    }
}

data class AddNodeChange(
    override val entryIndex: Int,
    override val nodePath: String,
    val nodeSource: String,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val propertyName: String? = null

    override val summary: String
        get() = "新增节点 $nodePath"

    override fun inverse(): DeviceTreeChange
    {
        return DeleteNodeChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            oldNodeSource = nodeSource
        )
    }
}

data class DeleteNodeChange(
    override val entryIndex: Int,
    override val nodePath: String,
    val oldNodeSource: String,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val propertyName: String? = null

    override val summary: String
        get() = "删除节点 $nodePath"

    override fun inverse(): DeviceTreeChange
    {
        return AddNodeChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            nodeSource = oldNodeSource
        )
    }
}

data class RenameNodeChange(
    override val entryIndex: Int,
    override val nodePath: String,
    val oldName: String,
    val newName: String,
    val newNodePath: String,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val propertyName: String? = null

    override val summary: String
        get() = "重命名节点 $nodePath → $newNodePath"

    override fun inverse(): DeviceTreeChange
    {
        return RenameNodeChange(
            entryIndex = entryIndex,
            nodePath = newNodePath,
            oldName = newName,
            newName = oldName,
            newNodePath = nodePath
        )
    }
}

data class CloneNodeChange(
    override val entryIndex: Int,
    val sourceNodePath: String,
    override val nodePath: String,
    val clonedNodeSource: String,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : DeviceTreeChange
{
    override val propertyName: String? = null

    override val summary: String
        get() = "克隆节点 $sourceNodePath → $nodePath"

    override fun inverse(): DeviceTreeChange
    {
        return DeleteNodeChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            oldNodeSource = clonedNodeSource
        )
    }
}

fun DeviceTreeChange.allowedNodePaths(): Set<String>
{
    return when (this)
    {
        is AddNodeChange -> setOf(nodePath)
        is DeleteNodeChange -> setOf(nodePath)
        is CloneNodeChange -> setOf(nodePath)
        is RenameNodeChange -> setOf(nodePath, newNodePath)
        else -> emptySet()
    }
}

fun DeviceTreeChange.allowedPropertyPath(): String?
{
    val name = propertyName ?: return null
    return "${nodePath.trimEnd('/')}/$name"
}

private fun format(raw: String?): String
{
    return raw?.ifBlank { "<boolean>" } ?: "<boolean>"
}
