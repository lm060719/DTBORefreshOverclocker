package io.mo.dtbooverclocker.feedback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Date
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {
    private val repo = "https://github.com/lm060719/DTBORefreshOverclocker"
    private val info = FeedbackDeviceInfo(
        device = "Xiaomi 23127PN0CC (shennong)",
        rom = "HyperOS OS2.0.1 / OS2.0.1.0.VNCCNXM",
        android = "Android 15 (API 35)",
        appVersion = "1.1.5 (115)",
        recommendedPanel = null,
        details = "Device: Xiaomi 23127PN0CC\nPanels in image (1):\n  - qcom,mdss_dsi_o1 [60, 120]Hz"
    )

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun `panel issue url prefills template fields`() {
        val url = FeedbackReport.issueUrl("$repo/", FeedbackType.PANEL_RECOMMEND, info)
        assertTrue(url.startsWith("$repo/issues/new?"))
        assertFalse("空格不能编码成 +", url.contains('+'))
        val p = params(url)
        assertEquals("panel_recommend.yml", p["template"])
        assertEquals(info.device, p["device"])
        assertEquals(info.rom, p["rom"])
        assertEquals(info.android, p["android"])
        assertEquals(info.appVersion, p["app_version"])
        assertEquals("未识别 / none", p["recommended_panel"])
        assertEquals(info.details, p["device_info"])
    }

    @Test
    fun `feature url only carries device`() {
        val p = params(FeedbackReport.issueUrl(repo, FeedbackType.FEATURE, info))
        assertEquals(setOf("template", "device"), p.keys)
    }

    @Test
    fun `long details are truncated in url`() {
        val long = info.copy(details = "x".repeat(20_000))
        val url = FeedbackReport.issueUrl(repo, FeedbackType.BUG, long)
        assertTrue(url.length < 8_000)
    }

    @Test
    fun `bundle contains info image and logs`() {
        val image = Files.createTempFile("dtbo", ".img").toFile().apply { writeBytes(ByteArray(64) { it.toByte() }) }
        try {
            val out = ByteArrayOutputStream()
            FeedbackReport.writeBundle(out, info, image, "log line".toByteArray())
            val entries = readZip(out.toByteArray())
            assertEquals(setOf("device_info.txt", "dtbo.img", "logs.txt"), entries.keys)
            assertEquals(info.details, entries.getValue("device_info.txt").toString(Charsets.UTF_8))
            assertTrue(image.readBytes().contentEquals(entries.getValue("dtbo.img")))
        } finally {
            image.delete()
        }
    }

    @Test
    fun `bundle without image still has info`() {
        val out = ByteArrayOutputStream()
        FeedbackReport.writeBundle(out, info, File("missing.img"), null)
        assertEquals(setOf("device_info.txt"), readZip(out.toByteArray()).keys)
    }

    @Test
    fun `bundle file name uses sanitized model`() {
        assertEquals("feedback_Xiaomi_23127PN0CC_19700101_000000.zip".length,
            FeedbackReport.bundleFileName(info, Date(0)).length)
        assertTrue(FeedbackReport.bundleFileName(info, Date(0)).startsWith("feedback_Xiaomi_23127PN0CC_"))
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }
}
