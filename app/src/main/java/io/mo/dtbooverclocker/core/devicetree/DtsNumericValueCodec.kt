package io.mo.dtbooverclocker.core.devicetree

/**
 * 对 DTC 反编译后的常见数值表示进行无符号解析与重新编码。
 *
 * Qualcomm DTBO 中同一个 32 位数值可能被 dtc 输出成 <0x...>、十进制 Cell、
 * 可打印字符串（例如 "aFX"）或 [61 46 58 00] 字节数组。刷新率模块只关心
 * 最终二进制数值，因此这里统一解码，并尽量保持原 Cell 的进制/宽度；字符串
 * 会安全改写为等价字节数组，避免重新生成不可打印转义字符串。
 */
object DtsNumericValueCodec
{
    fun decode(rawValue: String?): Long?
    {
        val raw = rawValue?.trim() ?: return null

        parseCells(raw)?.let { cells ->
            return when (cells.size)
            {
                1 -> cells[0]
                2 -> ((cells[0] and 0xffffffffL) shl 32) or
                    (cells[1] and 0xffffffffL)
                else -> null
            }
        }

        parseByteArray(raw)?.let(::bytesToLong)?.let { return it }
        parseSingleString(raw)?.let { content ->
            val bytes = unescapeDtsString(content) + byteArrayOf(0)
            return bytesToLong(bytes)
        }

        return null
    }

    fun encodeLike(
        originalRawValue: String?,
        value: Long
    ): String
    {
        require(value >= 0) {
            "DTS 数值不能为负数：$value"
        }

        val raw = originalRawValue?.trim().orEmpty()
        val cells = parseCells(raw)

        if (cells != null)
        {
            val originalTokens = raw
                .removePrefix("<")
                .removeSuffix(">")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }

            return when (originalTokens.size)
            {
                1 ->
                {
                    require(value <= 0xffffffffL) {
                        "单 Cell 属性无法保存超过 U32 的数值：$value"
                    }
                    val token = if (originalTokens[0].startsWith("0x", ignoreCase = true))
                    {
                        "0x" + value.toString(16)
                    }
                    else
                    {
                        value.toString()
                    }
                    "<$token>"
                }

                2 ->
                {
                    val high = (value ushr 32) and 0xffffffffL
                    val low = value and 0xffffffffL
                    val useHex = originalTokens.any { it.startsWith("0x", ignoreCase = true) }
                    val highToken = if (useHex) "0x${high.toString(16)}" else high.toString()
                    val lowToken = if (useHex) "0x${low.toString(16)}" else low.toString()
                    "<$highToken $lowToken>"
                }

                else -> defaultCells(value)
            }
        }

        val byteWidth = parseByteArray(raw)?.size
            ?: parseSingleString(raw)?.let { unescapeDtsString(it).size + 1 }

        if (byteWidth == 4 || byteWidth == 8)
        {
            if (byteWidth == 4)
            {
                require(value <= 0xffffffffL) {
                    "4 字节属性无法保存超过 U32 的数值：$value"
                }
            }
            return encodeByteArray(value, byteWidth)
        }

        return defaultCells(value)
    }

    fun encodeU32Like(
        originalRawValue: String?,
        value: Long
    ): String
    {
        require(value in 0..0xffffffffL) {
            "U32 超出范围：$value"
        }
        return encodeLike(originalRawValue, value)
    }

    private fun defaultCells(value: Long): String
    {
        return if (value <= 0xffffffffL)
        {
            "<0x${value.toString(16)}>"
        }
        else
        {
            val high = (value ushr 32) and 0xffffffffL
            val low = value and 0xffffffffL
            "<0x${high.toString(16)} 0x${low.toString(16)}>"
        }
    }

    private fun parseCells(raw: String): List<Long>?
    {
        if (!raw.startsWith('<') || !raw.endsWith('>') || '&' in raw)
        {
            return null
        }

        val tokens = raw
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty() || tokens.size > 2)
        {
            return null
        }

        return tokens.map { token ->
            parseUnsignedNumber(token) ?: return null
        }
    }

    private fun parseByteArray(raw: String): ByteArray?
    {
        if (!raw.startsWith('[') || !raw.endsWith(']'))
        {
            return null
        }

        val tokens = raw
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.size !in setOf(4, 8))
        {
            return null
        }

        return ByteArray(tokens.size) { index ->
            val value = tokens[index].removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                ?: return null
            if (value !in 0..255)
            {
                return null
            }
            value.toByte()
        }
    }

    private fun parseSingleString(raw: String): String?
    {
        val match = Regex("^\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"$").matchEntire(raw)
            ?: return null
        return match.groupValues[1]
    }

    private fun parseUnsignedNumber(token: String): Long?
    {
        val clean = token.trim().trimEnd(',')
        if (clean.startsWith('-'))
        {
            return null
        }

        return if (clean.startsWith("0x", ignoreCase = true))
        {
            clean.substring(2).toLongOrNull(16)
        }
        else
        {
            clean.toLongOrNull()
        }
    }

    private fun bytesToLong(bytes: ByteArray): Long?
    {
        if (bytes.size !in setOf(4, 8))
        {
            return null
        }

        var value = 0L
        bytes.forEach { byte ->
            value = (value shl 8) or (byte.toLong() and 0xffL)
        }
        return value
    }

    private fun encodeByteArray(value: Long, width: Int): String
    {
        val bytes = ByteArray(width)
        var remaining = value
        for (index in width - 1 downTo 0)
        {
            bytes[index] = (remaining and 0xffL).toByte()
            remaining = remaining ushr 8
        }
        require(remaining == 0L) {
            "数值 $value 无法放入 $width 字节"
        }

        return bytes.joinToString(
            prefix = "[",
            postfix = "]",
            separator = " "
        ) { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun unescapeDtsString(value: String): ByteArray
    {
        val output = mutableListOf<Byte>()
        var index = 0

        while (index < value.length)
        {
            val char = value[index]
            if (char != '\\' || index + 1 >= value.length)
            {
                output += char.code.toByte()
                index++
                continue
            }

            val next = value[index + 1]
            when (next)
            {
                'n' ->
                {
                    output += 10
                    index += 2
                }
                'r' ->
                {
                    output += 13
                    index += 2
                }
                't' ->
                {
                    output += 9
                    index += 2
                }
                '\\' ->
                {
                    output += '\\'.code.toByte()
                    index += 2
                }
                '"' ->
                {
                    output += '"'.code.toByte()
                    index += 2
                }
                '0' ->
                {
                    output += 0
                    index += 2
                }
                'x' ->
                {
                    if (index + 3 < value.length)
                    {
                        val hex = value.substring(index + 2, index + 4).toIntOrNull(16)
                        if (hex != null)
                        {
                            output += hex.toByte()
                            index += 4
                            continue
                        }
                    }
                    output += char.code.toByte()
                    index++
                }
                else ->
                {
                    output += next.code.toByte()
                    index += 2
                }
            }
        }

        return output.toByteArray()
    }
}
