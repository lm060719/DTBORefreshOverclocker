package io.mo.dtbooverclocker.model

data class DscParameters(
    val version: Int?,
    val bitsPerComponent: Int?,
    val bitsPerPixel: Int?,
    val sliceWidth: Int,
    val sliceHeight: Int,
    val slicePerPacket: Int,
    val blockPredictionEnabled: Boolean
)
