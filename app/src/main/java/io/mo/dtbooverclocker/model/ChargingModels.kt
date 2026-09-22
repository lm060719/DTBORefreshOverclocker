package io.mo.dtbooverclocker.model

/** Units describe the binding, not a guessed unit derived from the current value. */
data class ChargingParameter(
    val name: String,
    val label: String,
    val unit: String,
    val rawUnit: String = unit,
    val scale: Long = 1,
    val minimum: Long = 1,
    val maximum: Long = 0xffffffffL,
    val allowedValues: Set<Long> = emptySet(),
    val boolean: Boolean = false,
    val descendingStride: Int = 0
)

data class ChargingField(
    val parameter: ChargingParameter,
    val exists: Boolean,
    val rawValue: String?,
    val value: Long?,
    val issue: String? = null,
    val cellIndex: Int? = null,
    val group: String = "基础参数"
) {
    val inputKey: String get() = cellIndex?.let { "${parameter.name}[$it]" } ?: parameter.name
}

data class ChargingNode(
    val entryIndex: Int,
    val nodePath: String,
    val compatible: String?,
    val status: String?,
    val fields: List<ChargingField>,
    val otherProperties: List<Pair<String, String?>>,
    val targetLabel: String? = null
) {
    val key: String get() = "$entryIndex:$nodePath"
    val editableCount: Int get() = fields.count { it.issue == null }
}
