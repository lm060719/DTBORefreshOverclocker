package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.TimingUtils
import java.io.File

data class ActivePanelDetectionResult(
    val rawIdentifier: String,
    val normalizedIdentifier: String,
    val source: String,
    val matchedPanelIdentifier: String? = null
)

/** DTBO entry indices the bootloader applied on this boot (`androidboot.dtbo_idx`). */
data class AppliedDtboEntries(
    val indices: Set<Int>,
    val source: String
)

class ActivePanelDetector(
    private val rootDetector: RootDetector,
    private val logSink: (String) -> Unit = {}
) {
    suspend fun detect(): ActivePanelDetectionResult? {
        // 依次尝试：/proc/cmdline（全平台通用）→ 小米 mi_display sysfs → 其他厂商（OPPO/一加/三星）显示节点
        val result = detectFromCmdline()
            ?: detectFromMiDisplay()
            ?: detectFromOtherVendors()
        if (result != null) {
            logSink("[INFO] [PANEL-MATCH] 探测到在用面板：raw=${result.rawIdentifier}, normalized=${result.normalizedIdentifier}, 来源=${result.source}")
        } else {
            logSink("[WARN] [PANEL-MATCH] cmdline、小米显示驱动与其他厂商节点均未读到在用面板标识")
        }
        return result
    }

    /**
     * Android bootloaders report which DTBO entries they overlaid via `androidboot.dtbo_idx`;
     * it is the only reliable way to tell apart entries that carry identical panel nodes.
     */
    suspend fun detectAppliedDtboEntries(): AppliedDtboEntries? {
        val sources = listOf(
            listOf("getprop", "ro.boot.dtbo_idx") to "系统属性 ro.boot.dtbo_idx",
            listOf("cat", "/proc/bootconfig") to "/proc/bootconfig",
            listOf("cat", "/proc/cmdline") to "/proc/cmdline"
        )
        for ((command, source) in sources) {
            val result = rootDetector.runRoot(command, timeoutMs = 3000)
            if (!result.isSuccess) continue
            val raw = if (command.first() == "getprop") result.stdout.trim() else extractDtboIndexValue(result.stdout)
            val indices = raw?.let(::parseDtboIndices).orEmpty()
            if (!raw.isNullOrBlank()) logSink("[INFO] [PANEL-MATCH] $source dtbo_idx 原始值：$raw -> ${indices.sorted()}")
            if (indices.isNotEmpty()) return AppliedDtboEntries(indices, source)
        }
        return null
    }

    /**
     * Fallback when the bootloader does not report `dtbo_idx`: match the entries against the
     * device tree the kernel booted with. [workDir] holds the root-copied blob temporarily.
     */
    suspend fun detectAppliedDtboEntriesFromLiveTree(entries: List<ByteArray>, workDir: File): AppliedDtboEntries? {
        if (entries.size < 2) return null
        workDir.mkdirs()
        val copy = File(workDir, "live_fdt.dtb")
        try {
            val result = rootDetector.runRoot(listOf("dd", "if=/sys/firmware/fdt", "of=${copy.absolutePath}"), timeoutMs = 10_000)
            if (!result.isSuccess || !copy.isFile || copy.length() < 40) {
                logSink("[INFO] [PANEL-MATCH] 无法读取 /sys/firmware/fdt（exit=${result.exitCode}），跳过运行中设备树比对")
                return null
            }
            rootDetector.runRoot(listOf("chmod", "644", copy.absolutePath), timeoutMs = 3000)
            val live = FdtReader.readAllProperties(copy.readBytes())
            val match = LiveDeviceTreeMatcher.match(entries.map(FdtReader::readAllProperties), live)
            if (match == null) {
                logSink("[INFO] [PANEL-MATCH] 各 DTB 条目的 overlay 内容无差异，无法通过运行中设备树区分")
                return null
            }
            logSink("[INFO] [PANEL-MATCH] 运行中设备树比对（${match.discriminating} 个差异属性）：" +
                match.scores.joinToString { "DTB[${it.index}] 一致 ${it.matches} / 不符 ${it.mismatches}" })
            if (match.indices.isEmpty()) {
                logSink("[WARN] [PANEL-MATCH] 没有 DTB 条目与运行中设备树一致，镜像可能不是本次启动所用")
                return null
            }
            return AppliedDtboEntries(match.indices, "运行中设备树比对 (/sys/firmware/fdt)")
        } catch (e: Exception) {
            logSink("[WARN] [PANEL-MATCH] 运行中设备树比对失败：${e.message}")
            return null
        } finally {
            copy.delete()
        }
    }

    private suspend fun detectFromCmdline(): ActivePanelDetectionResult? {
        val result = rootDetector.runRoot(listOf("cat", "/proc/cmdline"), timeoutMs = 3000)
        if (!result.isSuccess || result.stdout.isBlank()) {
            logSink("[INFO] [PANEL-MATCH] 无法读取 /proc/cmdline（exit=${result.exitCode}）")
            return null
        }
        val cmdline = result.stdout.trim()
        val panelArgs = cmdline.split(Regex("\\s+")).filter { "dsi_display" in it || "mdss_dsi" in it || "panel" in it }
        logSink("[INFO] [PANEL-MATCH] cmdline 面板相关参数：${panelArgs.ifEmpty { listOf("无") }.joinToString(" ")}")

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
                logSink("[INFO] [PANEL-MATCH] $path：${result.stdout.trim().lines().take(5).joinToString(" | ")}")
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
                logSink("[INFO] [PANEL-MATCH] $path：${result.stdout.trim().lines().take(3).joinToString(" | ")}")
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
        private val dtboIndexRegex = Regex("""androidboot\.dtbo_idx\s*=\s*"?([0-9][0-9,\s]*)"?""")

        /** Finds the dtbo_idx value in bootconfig (`key = "0,3"`) or cmdline (`key=0,3`) text. */
        fun extractDtboIndexValue(text: String): String? =
            dtboIndexRegex.find(text)?.groupValues?.get(1)

        fun parseDtboIndices(raw: String): Set<Int> {
            val tokens = raw.trim().trim('"').split(',').map(String::trim).filter(String::isNotEmpty)
            val indices = tokens.mapNotNull(String::toIntOrNull).filter { it >= 0 }
            // A partially unparsable value is not trusted at all.
            return if (indices.size == tokens.size) indices.toSet() else emptySet()
        }

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
            detectedIdentifier: String,
            preferredEntries: Set<Int> = emptySet(),
            trace: (String) -> Unit = {}
        ): TimingCandidate? {
            if (candidates.isEmpty()) return null
            val normDetected = normalizeIdentifier(detectedIdentifier)

            val panelGrouped = candidates.groupBy { TimingUtils.parsePanelIdentifier(it.nodePath) }
            trace("[PANEL-MATCH] 待匹配 $normDetected；镜像中的面板（${panelGrouped.size}）：" +
                panelGrouped.keys.joinToString { "$it→${normalizeIdentifier(it)}" })
            // Substring matching alone would pick `..._cmd` for a detected `..._cmd_cphy` panel.
            val exact = panelGrouped.entries.firstOrNull { normalizeIdentifier(it.key) == normDetected }
            val matched = exact ?: panelGrouped.entries.firstOrNull { matchPanel(it.key, normDetected) }
            if (matched == null) {
                trace("[PANEL-MATCH] 精确与模糊规则均未命中")
                return null
            }
            // The same panel node exists in every DTB entry; only the applied entry takes effect.
            val entryCandidates = matched.value.filter { it.entryIndex in preferredEntries }
                .ifEmpty { matched.value }
            val normalCandidates = entryCandidates.filterNot { it.hasVendorDynamicMode }
            val best = normalCandidates.find { it.currentHz == 120 }
                ?: normalCandidates.find { it.currentHz == 144 }
                ?: normalCandidates.maxByOrNull { it.currentHz }
                ?: entryCandidates.firstOrNull()
            trace("[PANEL-MATCH] 命中 ${matched.key}（${if (exact != null) "精确" else "模糊"}规则），生效 DTB=${preferredEntries.sorted()}，候选档位：" +
                entryCandidates.joinToString { "DTB[${it.entryIndex}] ${it.currentHz}Hz" + if (it.hasVendorDynamicMode) "(动态)" else "" } +
                "；选中 ${best?.let { "DTB[${it.entryIndex}] ${it.currentHz}Hz ${it.nodePath}" } ?: "无"}")
            return best
        }
    }
}
