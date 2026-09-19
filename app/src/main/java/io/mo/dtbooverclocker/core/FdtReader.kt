package io.mo.dtbooverclocker.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight pure-Kotlin parser for Flattened Device Tree (DTB/FDT) binary blobs.
 * Used for property-level verification to ensure non-timing properties are preserved bit-for-bit.
 */
object FdtReader {
    const val FDT_MAGIC = 0xd00dfeed.toInt()
    private const val FDT_BEGIN_NODE = 1
    private const val FDT_END_NODE = 2
    private const val FDT_PROP = 3
    private const val FDT_NOP = 4
    private const val FDT_END = 9

    fun readAllProperties(fdtBytes: ByteArray): Map<String, ByteArray> {
        require(fdtBytes.size >= 40) { "DTB 数据过小" }
        val buffer = ByteBuffer.wrap(fdtBytes).order(ByteOrder.BIG_ENDIAN)

        val magic = buffer.getInt(0)
        require(magic == FDT_MAGIC) { "无效的 DTB 魔数：0x${magic.toUInt().toString(16)}" }

        val totalSize = buffer.getInt(4)
        val offDtStruct = buffer.getInt(8)
        val offDtStrings = buffer.getInt(12)
        val sizeDtStrings = buffer.getInt(32)
        val sizeDtStruct = buffer.getInt(36)

        require(offDtStruct in 40..fdtBytes.size) { "offDtStruct 越界" }
        require(offDtStrings in 40..fdtBytes.size) { "offDtStrings 越界" }
        require(offDtStruct + sizeDtStruct <= fdtBytes.size) { "struct block 越界" }
        require(offDtStrings + sizeDtStrings <= fdtBytes.size) { "strings block 越界" }

        val strings = fdtBytes.copyOfRange(offDtStrings, offDtStrings + sizeDtStrings)

        fun getString(offset: Int): String {
            require(offset in 0 until strings.size) { "字符串偏移越界：$offset" }
            var end = offset
            while (end < strings.size && strings[end] != 0.toByte()) {
                end++
            }
            return String(strings, offset, end - offset, Charsets.US_ASCII)
        }

        val nodeStack = mutableListOf<String>()
        val propertiesMap = mutableMapOf<String, ByteArray>()
        var p = offDtStruct
        val structEnd = offDtStruct + sizeDtStruct

        while (p < structEnd) {
            val tag = buffer.getInt(p)
            p += 4
            when (tag) {
                FDT_BEGIN_NODE -> {
                    var end = p
                    while (end < structEnd && fdtBytes[end] != 0.toByte()) {
                        end++
                    }
                    val name = String(fdtBytes, p, end - p, Charsets.US_ASCII)
                    nodeStack.add(name)
                    p = (end + 1 + 3) and 3.inv()
                }

                FDT_END_NODE -> {
                    if (nodeStack.isNotEmpty()) {
                        nodeStack.removeAt(nodeStack.size - 1)
                    }
                }

                FDT_PROP -> {
                    val valLen = buffer.getInt(p)
                    val nameOff = buffer.getInt(p + 4)
                    p += 8
                    require(p + valLen <= fdtBytes.size) { "属性值越界" }
                    val propVal = fdtBytes.copyOfRange(p, p + valLen)
                    p = (p + valLen + 3) and 3.inv()

                    val propName = getString(nameOff)
                    val nodePath = nodeStack.joinToString("/")
                    val fullPath = if (nodePath.isEmpty()) "/$propName" else "$nodePath/$propName"
                    propertiesMap[fullPath] = propVal
                }

                FDT_NOP -> {
                    // no-op, continue
                }

                FDT_END -> {
                    break
                }

                else -> {
                    error("未知的 FDT 标签：$tag 在偏移量 $p")
                }
            }
        }

        return propertiesMap
    }
}
