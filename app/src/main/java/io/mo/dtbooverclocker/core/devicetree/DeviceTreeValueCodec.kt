package io.mo.dtbooverclocker.core.devicetree

enum class NumberBase
{
    DECIMAL,
    HEX
}

object DeviceTreeValueCodec
{
    private val supportedTypedTypes = setOf(
        PropertyType.BOOLEAN,
        PropertyType.STRING,
        PropertyType.STRING_LIST,
        PropertyType.U32,
        PropertyType.U64,
        PropertyType.CELLS,
        PropertyType.BYTE_ARRAY
    )

    fun supportsTypedEditor(type: PropertyType): Boolean
    {
        return type in supportedTypedTypes
    }

    fun preferredNumberBase(property: DeviceTreeProperty?): NumberBase
    {
        val raw = property?.rawValue.orEmpty()
        return if (Regex("""0x[0-9a-fA-F]+""").containsMatchIn(raw))
        {
            NumberBase.HEX
        }
        else
        {
            NumberBase.DECIMAL
        }
    }

    fun editableText(
        type: PropertyType,
        rawValue: String?,
        numberBase: NumberBase = NumberBase.DECIMAL
    ): String
    {
        val raw = rawValue?.trim().orEmpty()
        return when (type)
        {
            PropertyType.BOOLEAN -> ""
            PropertyType.STRING -> parseQuotedStrings(raw).firstOrNull().orEmpty()
            PropertyType.STRING_LIST -> parseQuotedStrings(raw).joinToString("\n")
            PropertyType.U32 ->
            {
                val value = parseUnsignedCell(raw)
                if (value == null)
                {
                    raw
                }
                else if (numberBase == NumberBase.HEX)
                {
                    "0x" + value.toString(16)
                }
                else
                {
                    value.toString()
                }
            }
            PropertyType.U64,
            PropertyType.CELLS ->
            {
                raw.removePrefix("<").removeSuffix(">").trim()
            }
            PropertyType.BYTE_ARRAY ->
            {
                raw.removePrefix("[").removeSuffix("]").trim()
            }
            PropertyType.PHANDLE,
            PropertyType.UNKNOWN -> raw
        }
    }

    fun encode(
        type: PropertyType,
        text: String,
        numberBase: NumberBase = NumberBase.DECIMAL
    ): String?
    {
        val error = validate(type, text)
        require(error == null) { error ?: "属性值无效" }

        return when (type)
        {
            PropertyType.BOOLEAN -> null
            PropertyType.STRING -> "\"${escapeString(text)}\""
            PropertyType.STRING_LIST ->
            {
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(", ") { "\"${escapeString(it)}\"" }
            }
            PropertyType.U32 ->
            {
                val value = parseUnsignedNumber(text.trim())!!
                val token = if (numberBase == NumberBase.HEX)
                {
                    "0x" + value.toString(16)
                }
                else
                {
                    value.toString()
                }
                "<$token>"
            }
            PropertyType.U64,
            PropertyType.CELLS ->
            {
                val tokens = normalizeCellTokens(text)
                "<${tokens.joinToString(" ")}>"
            }
            PropertyType.BYTE_ARRAY ->
            {
                val tokens = normalizeByteTokens(text)
                "[${tokens.joinToString(" ")}]"
            }
            PropertyType.PHANDLE,
            PropertyType.UNKNOWN ->
            {
                text.trim().removeSuffix(";").trim().takeIf { it.isNotEmpty() }
            }
        }
    }

    fun validate(type: PropertyType, text: String): String?
    {
        return when (type)
        {
            PropertyType.BOOLEAN -> null
            PropertyType.STRING -> null
            PropertyType.STRING_LIST ->
            {
                if (text.lineSequence().none { it.trim().isNotEmpty() })
                {
                    "字符串列表至少需要一项"
                }
                else
                {
                    null
                }
            }
            PropertyType.U32 ->
            {
                val value = parseUnsignedNumber(text.trim())
                    ?: return "请输入 0～4294967295，支持 0x 十六进制"
                if (value > 0xffffffffL)
                {
                    "U32 超出范围：最大 4294967295"
                }
                else
                {
                    null
                }
            }
            PropertyType.U64,
            PropertyType.CELLS ->
            {
                val tokens = tokenize(text)
                if (tokens.isEmpty())
                {
                    "Cell 列表不能为空"
                }
                else if (tokens.any { token ->
                    val value = parseUnsignedNumber(token)
                    value == null || value > 0xffffffffL
                })
                {
                    "每个 Cell 必须是 32 位无符号整数，可使用 0x 十六进制"
                }
                else
                {
                    null
                }
            }
            PropertyType.BYTE_ARRAY ->
            {
                val tokens = tokenize(text)
                if (tokens.isEmpty())
                {
                    "Byte Array 不能为空"
                }
                else if (tokens.any { !Regex("""[0-9a-fA-F]{2}""").matches(it.removePrefix("0x").removePrefix("0X")) })
                {
                    "每个字节请使用两位 HEX，例如 01 ff a0"
                }
                else
                {
                    null
                }
            }
            PropertyType.PHANDLE,
            PropertyType.UNKNOWN ->
            {
                if (text.trim().isEmpty())
                {
                    "Raw DTS 值不能为空；Boolean 属性请明确选择 Boolean 类型"
                }
                else
                {
                    null
                }
            }
        }
    }

    fun displayName(type: PropertyType): String
    {
        return when (type)
        {
            PropertyType.STRING -> "String"
            PropertyType.STRING_LIST -> "String List"
            PropertyType.U32 -> "U32"
            PropertyType.U64 -> "U64 / Cells"
            PropertyType.CELLS -> "Cells"
            PropertyType.BYTE_ARRAY -> "Byte Array"
            PropertyType.BOOLEAN -> "Boolean"
            PropertyType.PHANDLE -> "Phandle"
            PropertyType.UNKNOWN -> "Raw"
        }
    }

    private fun parseQuotedStrings(raw: String): List<String>
    {
        return Regex("""\"((?:\\.|[^\"\\])*)\"""")
            .findAll(raw)
            .map { unescapeString(it.groupValues[1]) }
            .toList()
    }

    private fun escapeString(value: String): String
    {
        return buildString {
            value.forEach { char ->
                when (char)
                {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun unescapeString(value: String): String
    {
        val output = StringBuilder()
        var index = 0
        while (index < value.length)
        {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length)
            {
                when (val next = value[index + 1])
                {
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    '\\' -> output.append('\\')
                    '"' -> output.append('"')
                    else -> output.append(next)
                }
                index += 2
            }
            else
            {
                output.append(char)
                index++
            }
        }
        return output.toString()
    }

    private fun parseUnsignedCell(raw: String): Long?
    {
        val body = raw.removePrefix("<").removeSuffix(">").trim()
        val tokens = tokenize(body)
        return if (tokens.size == 1) parseUnsignedNumber(tokens.first()) else null
    }

    private fun parseUnsignedNumber(token: String): Long?
    {
        val clean = token.trim().trimEnd(',')
        if (clean.startsWith("-"))
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

    private fun normalizeCellTokens(text: String): List<String>
    {
        return tokenize(text).map { token ->
            val value = parseUnsignedNumber(token)!!
            if (token.startsWith("0x", ignoreCase = true))
            {
                "0x" + value.toString(16)
            }
            else
            {
                value.toString()
            }
        }
    }

    private fun normalizeByteTokens(text: String): List<String>
    {
        return tokenize(text).map { token ->
            token.removePrefix("0x").removePrefix("0X").lowercase()
        }
    }

    private fun tokenize(text: String): List<String>
    {
        return text
            .trim()
            .removePrefix("<")
            .removeSuffix(">")
            .removePrefix("[")
            .removeSuffix("]")
            .split(Regex("""[\s,]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
