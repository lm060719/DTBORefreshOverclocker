package io.mo.dtbooverclocker.core.devicetree

import java.util.UUID

sealed interface DeviceTreeChange
{
    val id: String
    val entryIndex: Int
    val nodePath: String
    val propertyName: String
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

private fun format(raw: String?): String
{
    return raw?.ifBlank { "<boolean>" } ?: "<boolean>"
}
