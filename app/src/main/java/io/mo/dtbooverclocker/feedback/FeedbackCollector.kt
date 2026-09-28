package io.mo.dtbooverclocker.feedback

import android.os.Build
import io.mo.dtbooverclocker.BuildConfig
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.RootState
import io.mo.dtbooverclocker.model.SlotInfo
import io.mo.dtbooverclocker.ui.components.TimingUtils
import java.util.concurrent.TimeUnit

/** 采集反馈所需的设备与工作区信息。会调用 `getprop`，请在 IO 线程执行。 */
object FeedbackCollector {
    /** 厂商 ROM 版本属性，按顺序取第一个非空值。 */
    private val romProperties = listOf(
        "ro.mi.os.version.name" to "HyperOS",
        "ro.miui.ui.version.name" to "MIUI",
        "ro.build.version.oplusrom" to "ColorOS",
        "ro.build.version.realmeui" to "realme UI",
        "ro.build.version.opporom" to "ColorOS",
        "ro.vivo.os.build.display.id" to "",
        "ro.build.version.magic" to "MagicOS",
        "ro.build.version.emui" to "",
        "ro.flyme.version.id" to ""
    )

    fun collect(
        rootState: RootState,
        slotInfo: SlotInfo?,
        workspace: DtboWorkspace?,
        activeDtboEntries: Set<Int>,
        activePanelIdentifier: String?,
        activePanelSource: String?
    ): FeedbackDeviceInfo {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        val vendorRom = romProperties.firstNotNullOfOrNull { (key, label) ->
            getprop(key)?.let { value -> if (label.isEmpty() || value.startsWith(label)) value else "$label $value" }
        }
        val rom = listOfNotNull(vendorRom, Build.DISPLAY).distinct().joinToString(" / ")
        val android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        val details = buildString {
            appendLine("App: ${BuildConfig.APPLICATION_ID} $appVersion, buildType=${BuildConfig.BUILD_TYPE}, sourceId=${BuildConfig.SOURCE_ID}")
            appendLine("Device: $device, product=${Build.PRODUCT}")
            appendLine("Board / Hardware / SoC: ${Build.BOARD} / ${Build.HARDWARE} / ${socModel()}")
            appendLine("ROM: $rom")
            appendLine("Incremental: ${Build.VERSION.INCREMENTAL}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine("Android: $android, security patch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("Kernel: ${System.getProperty("os.version") ?: "unknown"}")
            appendLine("Root: ${if (rootState.granted) "granted" else "not granted"} (${rootState.detail})")
            appendLine("Slot: ${slotInfo?.let { "${it.label}, ${it.blockDevice}" } ?: "unknown"}")
            if (workspace == null) {
                appendLine("DTBO: 未导入 / not imported")
            } else {
                val source = workspace.sourceImage
                appendLine("DTBO: ${source?.sourceMode ?: "unknown"}, entries=${workspace.binaryImage.entries.size}, " +
                    "size=${source?.containerSize ?: workspace.inputImage.length()}, AVB=${source?.avbProtectionState ?: "unknown"}")
                appendLine("DTBO SHA-256: ${source?.sha256 ?: "unknown"}")
                appendLine("Applied DTB (dtbo_idx): ${activeDtboEntries.sorted().ifEmpty { null } ?: "unknown"}")
                appendLine("Recommended panel: ${activePanelIdentifier ?: "none"}${activePanelSource?.let { " (source: $it)" }.orEmpty()}")
                val panels = workspace.candidates
                    .groupBy { TimingUtils.parsePanelIdentifier(it.nodePath) }
                    .map { (panel, list) -> "$panel ${list.map { it.currentHz }.distinct().sorted()}Hz DTB${list.map { it.entryIndex }.distinct().sorted()}" }
                appendLine("Panels in image (${panels.size}):")
                panels.forEach { appendLine("  - $it") }
            }
        }.trimEnd()

        return FeedbackDeviceInfo(device, rom, android, appVersion, activePanelIdentifier, details)
    }

    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"

    private fun getprop(key: String): String? = runCatching {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor(2, TimeUnit.SECONDS)
        value.ifEmpty { null }
    }.getOrNull()
}
