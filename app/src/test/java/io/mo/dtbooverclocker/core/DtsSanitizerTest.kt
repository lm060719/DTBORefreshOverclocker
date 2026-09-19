package io.mo.dtbooverclocker.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtsSanitizerTest {

    @Test
    fun testUnescapeVoltPara() {
        val input = "4200\\0670\\04500\\0450"
        val bytes = DtsSanitizer.unescapeDtsStringToBytes(input)
        val expected = "4200\u0000670\u00004500\u0000450\u0000".toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun testUnescapeRegulatorMicrovolt() {
        // 800,000 uV = 0x000c3500
        val inputMin = "\\0\\f5"
        val bytesMin = DtsSanitizer.unescapeDtsStringToBytes(inputMin)
        assertArrayEquals(byteArrayOf(0x00, 0x0c, 0x35, 0x00), bytesMin)

        // 3,300,000 uV = 0x00324b00
        val inputMax = "\\02K"
        val bytesMax = DtsSanitizer.unescapeDtsStringToBytes(inputMax)
        assertArrayEquals(byteArrayOf(0x00, 0x32, 0x4b, 0x00), bytesMax)
    }

    @Test
    fun testSanitizeDtsText() {
        val dts = """
            /dts-v1/;
            / {
                model = "My Phone";
                volt_para1 = "4200\0670\04500\0450";
                regulator-min-microvolt = "\0\f5";
                regulator-max-microvolt = "\02K";
                normal_str = "hello world";
            };
        """.trimIndent()

        val sanitized = DtsSanitizer.sanitize(dts)

        // Normal strings should be left alone
        assertTrue(sanitized.contains("""model = "My Phone";"""))
        assertTrue(sanitized.contains("""normal_str = "hello world";"""))

        // Strings with \0 should be converted to byte arrays [ ... ]
        assertTrue(sanitized.contains("volt_para1 = [ 34 32 30 30 00 36 37 30 00 34 35 30 30 00 34 35 30 00 ];"))
        assertTrue(sanitized.contains("regulator-min-microvolt = [ 00 0c 35 00 ];"))
        assertTrue(sanitized.contains("regulator-max-microvolt = [ 00 32 4b 00 ];"))
    }
}
