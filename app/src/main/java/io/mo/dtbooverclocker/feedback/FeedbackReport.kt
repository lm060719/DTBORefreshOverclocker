package io.mo.dtbooverclocker.feedback

import java.io.File
import java.io.OutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 反馈类型，与 `.github/ISSUE_TEMPLATE/` 下的 Issue Form 一一对应。 */
enum class FeedbackType(val template: String, val needsBundle: Boolean) {
    BUG("bug_report.yml", needsBundle = true),
    PANEL_RECOMMEND("panel_recommend.yml", needsBundle = true),
    FEATURE("feature_request.yml", needsBundle = false)
}

/** 预填到 Issue Form 的设备信息；字段名即模板中的 `id`。 */
data class FeedbackDeviceInfo(
    val device: String,
    val rom: String,
    val android: String,
    val appVersion: String,
    /** App 推荐的在用面板；null 表示未识别。 */
    val recommendedPanel: String?,
    /** 完整的多行诊断信息，写入反馈包并预填到 `device_info` 字段。 */
    val details: String
)

object FeedbackReport {
    // GitHub 对过长的 URL 会直接报错；详细信息以反馈包为准，URL 中只放截断版本。
    private const val MAX_DETAILS_IN_URL = 4000

    fun issueUrl(repositoryUrl: String, type: FeedbackType, info: FeedbackDeviceInfo): String {
        val params = buildList {
            add("template" to type.template)
            add("device" to info.device)
            if (type != FeedbackType.FEATURE) {
                add("rom" to info.rom)
                add("android" to info.android)
                add("app_version" to info.appVersion)
                add("device_info" to info.details.truncate(MAX_DETAILS_IN_URL))
            }
            if (type == FeedbackType.PANEL_RECOMMEND) {
                add("recommended_panel" to (info.recommendedPanel ?: "未识别 / none"))
            }
        }
        return "${repositoryUrl.trimEnd('/')}/issues/new?" +
            params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    fun bundleFileName(info: FeedbackDeviceInfo, now: Date = Date()): String {
        val model = info.device.substringBefore(" (").replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        return "feedback_${model.ifEmpty { "device" }}_$timestamp.zip"
    }

    /**
     * 写出反馈包：`device_info.txt`、原始镜像 `dtbo.img`（如有）、`logs.txt`（如有）。
     * GitHub 不接受 `.img` 附件，因此统一打成 zip 上传。
     */
    fun writeBundle(out: OutputStream, info: FeedbackDeviceInfo, image: File?, logs: ByteArray?) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("device_info.txt"))
            zip.write(info.details.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (image != null && image.isFile) {
                zip.putNextEntry(ZipEntry("dtbo.img"))
                image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (logs != null) {
                zip.putNextEntry(ZipEntry("logs.txt"))
                zip.write(logs)
                zip.closeEntry()
            }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun String.truncate(max: Int): String =
        if (length <= max) this else take(max) + "\n…（已截断，完整信息见反馈包 device_info.txt）"
}
