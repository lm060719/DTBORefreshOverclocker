package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.TimingUtils

data class ActivePanelDetectionResult(
    val rawIdentifier: String,
    val normalizedIdentifier: String,
    val source: String,
    val matchedPanelIdentifier: String? = null
)

class ActivePanelDetector(
    private val rootDetector: RootDetector
) {
    suspend fun detect(): ActivePanelDetectionResult? {
        // 1. Tier 1: /proc/cmdline (全平台通用，包含启动引导加载器传入的面板)
        val cmdlineResult = detectFromCmdline()
        if (cmdlineResult != null) return cmdlineResult

        // 2. Tier 2: 小米 / Redmi / POCO mi_display 驱动 sysfs
        val miResult = detectFromMiDisplay()
        if (miResult != null) return miResult

        // 3. Tier 3: 其他厂商（OPPO/一加/三星）显示节点
        val vendorResult = detectFromOtherVendors()
        if (vendorResult != null) return vendorResult

        return null
    }

    private suspend fun detectFromCmdline(): ActivePanelDetectionResult? {
        val result = rootDetector.runRoot(listOf("cat", "/proc/cmdline"), timeoutMs = 3000)
        if (!result.isSuccess || result.stdout.isBlank()) return null
        val cmdline = result.stdout.trim()

        // 匹配 msm_drm.dsi_display0=qcom,mdss_dsi_o1_42_02_0a_dsc_cmd: 等
        val dsiRegex = Regex("""(?:msm_drm\.dsi_display\d*|mdss_dsi\.display\d*)=([a-zA-Z0-9,._-]+)""")
        dsiRegex.find(cmdline)?.let { match ->
            val raw = match.groupValues[1].trimEnd(':').trim()
            if (raw.isNotBlank()) {
                return ActivePanelDetectionResult(
                    rawIdentifier = raw,
                    normalizedIdentifier = normalizeIdentifier(raw),
                    source = "内核启动参数 (msm_drm.dsi_display)"
                )
            }
        }

        // 匹配 androidboot.panel=...
        val bootPanelRegex = Regex("""androidboot\.panel(?:_name)?=([a-zA-Z0-9,._-]+)""")
        bootPanelRegex.find(cmdline)?.let { match ->
            val raw = match.groupValues[1].trimEnd(':').trim()
            if (raw.isNotBlank()) {
                return ActivePanelDetectionResult(
                    rawIdentifier = raw,
                    normalizedIdentifier = normalizeIdentifier(raw),
                    source = "内核启动参数 (androidboot.panel)"
                )
            }
        }

        return null
    }

    private suspend fun detectFromMiDisplay(): ActivePanelDetectionResult? {
        val paths = listOf(
            "/sys/class/mi_display/disp-DSI-0/panel_info",
            "/sys/class/mi_display/disp-DSI-1/panel_info",
            "/sys/class/mi_display/disp_feature/panel_info"
        )
        for (path in paths) {
            val result = rootDetector.runRoot(listOf("cat", path), timeoutMs = 2000)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                val panelNameRegex = Regex("""panel_name=([a-zA-Z0-9,._-]+)""")
                val match = panelNameRegex.find(result.stdout)
                if (match != null) {
                    val raw = match.groupValues[1].trim()
                    if (raw.isNotBlank()) {
                        return ActivePanelDetectionResult(
                            rawIdentifier = raw,
                            normalizedIdentifier = normalizeIdentifier(raw),
                            source = "小米显示驱动 ($path)"
                        )
                    }
                }
            }
        }
        return null
    }

    private suspend fun detectFromOtherVendors(): ActivePanelDetectionResult? {
        val vendorNodes = listOf(
            "/sys/kernel/oplus_display/panel_name" to "OPPO/OnePlus 显示驱动",
            "/proc/touchpanel/panel_name" to "触控驱动面板标识",
            "/sys/class/lcd/panel/panel_name" to "LCD 显示驱动"
        )
        for ((path, sourceName) in vendorNodes) {
            val result = rootDetector.runRoot(listOf("cat", path), timeoutMs = 2000)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                val raw = result.stdout.trim().lines().firstOrNull()?.trim().orEmpty()
                if (raw.isNotBlank() && !raw.startsWith("error", ignoreCase = true)) {
                    return ActivePanelDetectionResult(
                        rawIdentifier = raw,
                        normalizedIdentifier = normalizeIdentifier(raw),
                        source = sourceName
                    )
                }
            }
        }
        return null
    }

    companion object {
        fun normalizeIdentifier(name: String): String {
            return name
                .removePrefix("qcom,")
                .removePrefix("mdss_dsi_")
                .removePrefix("dsi_")
                .trim()
                .lowercase()
        }

        fun matchPanel(
            panelIdentifier: String,
            detectedIdentifier: String
        ): Boolean {
            val normPanel = normalizeIdentifier(panelIdentifier)
            val normDetected = normalizeIdentifier(detectedIdentifier)
            if (normPanel == normDetected) return true
            if (normPanel.contains(normDetected) || normDetected.contains(normPanel)) return true

            val tokens = normDetected.split('_', '-', ',').filter { it.length >= 2 }
            if (tokens.size >= 2 && tokens.all { normPanel.contains(it) }) return true

            return false
        }

        fun findBestMatchCandidate(
            candidates: List<TimingCandidate>,
            detectedIdentifier: String
        ): TimingCandidate? {
            if (candidates.isEmpty()) return null
            val normDetected = normalizeIdentifier(detectedIdentifier)

            val panelGrouped = candidates.groupBy { TimingUtils.parsePanelIdentifier(it.nodePath) }
            for ((panelId, panelCandidates) in panelGrouped) {
                if (matchPanel(panelId, normDetected)) {
                    val normalCandidates = panelCandidates.filterNot { it.hasVendorDynamicMode }
                    return normalCandidates.find { it.currentHz == 120 }
                        ?: normalCandidates.find { it.currentHz == 144 }
                        ?: normalCandidates.maxByOrNull { it.currentHz }
                        ?: panelCandidates.firstOrNull()
                }
            }

            return null
        }
    }
}
