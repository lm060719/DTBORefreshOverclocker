package io.mo.dtbooverclocker.core

import java.io.ByteArrayOutputStream

/**
 * Sanitizes DTS text produced by `dtc -I dtb -O dts`.
 *
 * Background:
 * Android NDK's `dtc` decompiler uses heuristics to identify string properties.
 * In vendor DTBs (especially Qualcomm/Xiaomi platforms), many properties such as
 * MCA Buck Charger curves, JEITA battery parameters, and PMIC regulators contain
 * internal null bytes `\0` (e.g. "4200\0670\04500\0450" or "\0\f5").
 *
 * When recompiled (`dtc -I dts -O dtb`), `dtc`'s lexer parses `\0` followed by
 * digits `0`-`7` as an octal escape sequence (`\[0-7]{1,3}`), destroying null
 * terminators and corrupting digits into unintended bytes. This causes kernel
 * panic or emergency shutdown on boot.
 *
 * This sanitizer converts any DTS string containing `\0` into DTC's raw byte
 * stream format `[ xx xx ... ]`, which DTC compiles with 100% bit-for-bit fidelity
 * without escape re-interpretation.
 */
object DtsSanitizer {

    private val STRING_LITERAL_REGEX = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"""")

    fun sanitize(dtsText: String): String {
        if (!dtsText.contains("""\0""")) {
            return dtsText
        }

        return STRING_LITERAL_REGEX.replace(dtsText) { matchResult ->
            val content = matchResult.groupValues[1]
            if (content.contains("""\0""")) {
                val rawBytes = unescapeDtsStringToBytes(content)
                val hexString = rawBytes.joinToString(" ") { "%02x".format(it) }
                "[ $hexString ]"
            } else {
                matchResult.value
            }
        }
    }

    /**
     * Unescapes a DTS string literal content into its exact raw byte representation.
     * Appends the final null byte (0x00) which DTC string literals always imply.
     */
    fun unescapeDtsStringToBytes(content: String): ByteArray {
        val out = ByteArrayOutputStream(content.length + 4)
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == '\\' && i + 1 < content.length) {
                val next = content[i + 1]
                when (next) {
                    // DTC decompiled null byte: always emits single null byte.
                    // Subsequent digits (e.g. \0670 or \02K) must NOT be folded as octal.
                    '0' -> {
                        out.write(0)
                        i += 2
                    }
                    'a' -> { out.write(7); i += 2 }
                    'b' -> { out.write(8); i += 2 }
                    't' -> { out.write(9); i += 2 }
                    'n' -> { out.write(10); i += 2 }
                    'v' -> { out.write(11); i += 2 }
                    'f' -> { out.write(12); i += 2 }
                    'r' -> { out.write(13); i += 2 }
                    '\\' -> { out.write('\\'.code); i += 2 }
                    '"' -> { out.write('"'.code); i += 2 }
                    '\'' -> { out.write('\''.code); i += 2 }
                    'x', 'X' -> {
                        if (i + 3 < content.length) {
                            val hexPart = content.substring(i + 2, i + 4)
                            val byteVal = hexPart.toIntOrNull(16)
                            if (byteVal != null) {
                                out.write(byteVal)
                                i += 4
                                continue
                            }
                        }
                        out.write(c.code)
                        i++
                    }
                    in '1'..'7' -> {
                        // Octal escape starting with non-zero digit
                        var len = 1
                        while (len < 3 && i + 1 + len < content.length && content[i + 1 + len] in '0'..'7') {
                            len++
                        }
                        val octPart = content.substring(i + 1, i + 1 + len)
                        val byteVal = octPart.toIntOrNull(8) ?: 0
                        out.write(byteVal and 0xff)
                        i += 1 + len
                    }
                    else -> {
                        out.write(next.code)
                        i += 2
                    }
                }
            } else {
                out.write(c.code)
                i++
            }
        }
        // Every string in DTS format has an implicit null terminator
        out.write(0)
        return out.toByteArray()
    }
}
