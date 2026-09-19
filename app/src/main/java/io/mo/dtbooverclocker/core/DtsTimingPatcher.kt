package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object DtsTimingPatcher {
    private val refreshAliases = listOf(
        "qcom,mdss-dsi-panel-framerate",
        "qcom,mdss-dsi-panel-refresh-rate",
        "panel-framerate",
        "refresh-rate"
    )

    private val pixelClockAliases = listOf(
        "qcom,mdss-dsi-panel-clockrate",
        "qcom,mdss-dsi-panel-clock-rate",
        "pixel-clock",
        "clock-frequency"
    )

    private val hActiveAliases = listOf("qcom,mdss-dsi-panel-width", "hactive", "h-active")
    private val vActiveAliases = listOf("qcom,mdss-dsi-panel-height", "vactive", "v-active")
    private val hFrontPorchAliases = listOf("qcom,mdss-dsi-h-front-porch", "hfront-porch", "h-front-porch")
    private val hBackPorchAliases = listOf("qcom,mdss-dsi-h-back-porch", "hback-porch", "h-back-porch")
    private val hSyncAliases = listOf("qcom,mdss-dsi-h-pulse-width", "hsync-len", "h-sync-len")
    private val vFrontPorchAliases = listOf("qcom,mdss-dsi-v-front-porch", "vfront-porch", "v-front-porch")
    private val vBackPorchAliases = listOf("qcom,mdss-dsi-v-back-porch", "vback-porch", "v-back-porch")
    private val vSyncAliases = listOf("qcom,mdss-dsi-v-pulse-width", "vsync-len", "v-sync-len")

    fun analyzeEntry(entryIndex: Int, dtsFile: File): List<TimingCandidate> {
        val text = dtsFile.readText()
        val nodes = parseNodeRanges(text)
        val refreshMatches = findAllProperties(text, refreshAliases)

        return refreshMatches.mapNotNull { refreshProp ->
            val owner = nodes
                .asSequence()
                .filter { refreshProp.absoluteStart in it.start until it.endExclusive }
                .minByOrNull { it.endExclusive - it.start }
                ?: return@mapNotNull null

            val nodeText = text.substring(owner.start, owner.endExclusive)
            val nodeRefresh = findFirstProperty(nodeText, refreshAliases) ?: return@mapNotNull null
            val hz = nodeRefresh.value.toIntOrNullExact() ?: return@mapNotNull null
            if (hz !in 1..1000) return@mapNotNull null

            val clock = valueOf(nodeText, pixelClockAliases)
                ?: findParentClock(text, owner, nodes)

            TimingCandidate(
                id = "$entryIndex:${owner.start}:${owner.path}",
                entryIndex = entryIndex,
                dtsFile = dtsFile,
                nodePath = owner.path,
                nodeStart = owner.start,
                nodeEndExclusive = owner.endExclusive,
                currentHz = hz,
                pixelClockHz = clock,
                hActive = intValueOf(nodeText, hActiveAliases),
                vActive = intValueOf(nodeText, vActiveAliases),
                hFrontPorch = intValueOf(nodeText, hFrontPorchAliases),
                hBackPorch = intValueOf(nodeText, hBackPorchAliases),
                hSync = intValueOf(nodeText, hSyncAliases),
                vFrontPorch = intValueOf(nodeText, vFrontPorchAliases),
                vBackPorch = intValueOf(nodeText, vBackPorchAliases),
                vSync = intValueOf(nodeText, vSyncAliases),
                hasOpaquePanelTimings = nodeText.contains("qcom,mdss-dsi-panel-timings")
            )
        }.distinctBy { it.id }
    }

    private fun findParentClock(
        fullText: String,
        owner: NodeRange,
        allNodes: List<NodeRange>
    ): Long? {
        val parentPath = owner.path.substringBeforeLast('/', "")
        if (parentPath.isEmpty()) return null

        val candidates = allNodes.filter {
            it.path == parentPath || it.path == parentPath.substringBeforeLast('/', "")
        }
        for (candidate in candidates) {
            val parentText = fullText.substring(candidate.start, candidate.endExclusive)
            val clk = valueOf(parentText, pixelClockAliases)
            if (clk != null) return clk
        }
        return null
    }

    fun patch(
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
        customParams: CustomTimingParams? = null
    ): PatchTextResult {
        if (mode != PatchMode.DELETE_EXISTING) {
            require(targetHz in 30..360) { "目标刷新率必须在 30..360 Hz 范围内" }
            require(targetHz != candidate.currentHz) { "目标刷新率与当前刷新率相同" }
        }

        val fullText = candidate.dtsFile.readText()
        require(candidate.nodeStart >= 0 && candidate.nodeEndExclusive <= fullText.length) {
            "DTS 节点范围已失效，请重新解析镜像"
        }

        val originalNodeText = fullText.substring(candidate.nodeStart, candidate.nodeEndExclusive)

        return when (mode) {
            PatchMode.OVERWRITE_EXISTING -> {
                val (patchedNode, changes, warnings) = patchNodeContent(
                    candidate = candidate,
                    nodeText = originalNodeText,
                    targetHz = targetHz,
                    strategy = strategy,
                    customParams = customParams
                )
                val patchedFullText = buildString(fullText.length + 128) {
                    append(fullText, 0, candidate.nodeStart)
                    append(patchedNode)
                    append(fullText, candidate.nodeEndExclusive, fullText.length)
                }
                PatchTextResult(text = patchedFullText, changes = changes, warnings = warnings)
            }

            PatchMode.APPEND_NEW -> {
                val allNodes = parseNodeRanges(fullText)
                val newNodeName = generateUniqueSiblingNodeName(allNodes, candidate, targetHz)
                val clonedHeaderNodeText = replaceNodeHeader(originalNodeText, newNodeName)

                val (patchedClonedNode, innerChanges, warnings) = patchNodeContent(
                    candidate = candidate,
                    nodeText = clonedHeaderNodeText,
                    targetHz = targetHz,
                    strategy = strategy,
                    customParams = customParams
                )

                val baseIndent = originalNodeText.lines().firstOrNull()?.takeWhile { it.isWhitespace() } ?: ""
                val indentedNewNode = if (patchedClonedNode.startsWith(baseIndent)) {
                    patchedClonedNode
                } else {
                    baseIndent + patchedClonedNode.trimStart()
                }

                val patchedFullText = buildString(fullText.length + indentedNewNode.length + 32) {
                    append(fullText, 0, candidate.nodeEndExclusive)
                    append("\n\n")
                    append(indentedNewNode)
                    append(fullText, candidate.nodeEndExclusive, fullText.length)
                }

                val changes = mutableListOf<String>()
                changes += "➕ 新增独立时序节点: $newNodeName ($targetHz Hz)"
                changes += "基准模板节点: ${candidate.nodePath.substringAfterLast('/')} (${candidate.currentHz} Hz)"
                changes += "保留原有档位: ${candidate.currentHz} Hz 完好保留"
                changes += innerChanges

                PatchTextResult(text = patchedFullText, changes = changes, warnings = warnings)
            }

            PatchMode.DELETE_EXISTING -> {
                val allNodes = parseNodeRanges(fullText)
                val parentPath = candidate.nodePath.substringBeforeLast('/', "")
                val siblings = allNodes.filter { it.path.substringBeforeLast('/', "") == parentPath }
                val remainingSiblings = siblings.filter { it.path != candidate.nodePath }
                require(remainingSiblings.isNotEmpty()) {
                    "该屏幕面板仅包含一个时序档位节点，删除会导致屏幕无可用时序无法开机，禁止删除。"
                }

                val start = candidate.nodeStart
                var end = candidate.nodeEndExclusive
                if (end < fullText.length && fullText[end] == '\r') end++
                if (end < fullText.length && fullText[end] == '\n') end++

                var patchedFullText = buildString(fullText.length) {
                    append(fullText, 0, start)
                    append(fullText, end, fullText.length)
                }

                val changes = mutableListOf<String>()
                val warnings = mutableListOf<String>()
                val nodeName = candidate.nodePath.substringAfterLast('/')
                changes += "🗑 移除时序节点: $nodeName (${candidate.currentHz} Hz)"
                changes += "节点路径: ${candidate.nodePath}"
                changes += "剩余档位数量: ${remainingSiblings.size} 个 (${remainingSiblings.joinToString { it.path.substringAfterLast('/') }})"

                // 检查 native-mode 引用
                val openHeaderRegex = Regex("""^\s*(?:([A-Za-z0-9_.-]+):\s*)?([A-Za-z0-9,._@+\-/#]+)\s*\{""", RegexOption.MULTILINE)
                val headerMatch = openHeaderRegex.find(originalNodeText)
                val deletedLabel = headerMatch?.groups?.get(1)?.value

                val nativeModeRegex = Regex("""(?m)^(\s*native-mode\s*=\s*<)([^>]+)(>\s*;)""")
                val nativeMatch = nativeModeRegex.find(patchedFullText)
                if (nativeMatch != null) {
                    val refContent = nativeMatch.groups[2]!!.value.trim()
                    val isReferencingDeleted = (deletedLabel != null && refContent.contains("&$deletedLabel")) ||
                        refContent.contains("&{${candidate.nodePath}}") ||
                        refContent.contains("&$nodeName")
                    if (isReferencingDeleted) {
                        val targetSibling = remainingSiblings.first()
                        val targetSibText = fullText.substring(targetSibling.start, targetSibling.endExclusive)
                        val targetSibLabel = openHeaderRegex.find(targetSibText)?.groups?.get(1)?.value
                        val newRef = if (targetSibLabel != null) "&$targetSibLabel" else "&{${targetSibling.path}}"
                        patchedFullText = patchedFullText.replaceRange(
                            nativeMatch.groups[2]!!.range,
                            newRef
                        )
                        changes += "🔄 默认开机档位 (native-mode) 原指向被删节点，已自动重定向为 $newRef"
                        warnings += "已自动修正 native-mode 指向剩余的时序档位。"
                    }
                }

                PatchTextResult(text = patchedFullText, changes = changes, warnings = warnings)
            }
        }
    }

    private fun generateUniqueSiblingNodeName(
        allNodes: List<NodeRange>,
        candidate: TimingCandidate,
        targetHz: Int
    ): String {
        val parentPath = candidate.nodePath.substringBeforeLast('/', "")
        val currentName = candidate.nodePath.substringAfterLast('/')
        val siblingNames = allNodes
            .filter { it.path.substringBeforeLast('/', "") == parentPath }
            .map { it.path.substringAfterLast('/') }
            .toSet()

        // 1. Check for @<number> pattern (e.g. timing@0, mode@0)
        val atRegex = Regex("""^([A-Za-z0-9,._\-/#]+)@([0-9a-fA-F]+)$""")
        val atMatch = atRegex.matchEntire(currentName)
        if (atMatch != null) {
            val prefix = atMatch.groupValues[1]
            val numbers = siblingNames.mapNotNull { sib ->
                val m = Regex("""^${Regex.escape(prefix)}@([0-9a-fA-F]+)$""").matchEntire(sib)
                m?.groupValues?.get(1)?.toLongOrNull(10) ?: m?.groupValues?.get(1)?.toLongOrNull(16)
            }
            var nextIndex = (numbers.maxOrNull() ?: 0L) + 1L
            while ("$prefix@$nextIndex" in siblingNames) {
                nextIndex++
            }
            return "$prefix@$nextIndex"
        }

        // 2. Check for -<number> or _<number> pattern (e.g. timing-0, qcom,mdss-dsi-panel-timing-0, timing_0)
        val sepRegex = Regex("""^([A-Za-z0-9,._@\-/#]+)([-_])(\d+)$""")
        val sepMatch = sepRegex.matchEntire(currentName)
        if (sepMatch != null) {
            val prefix = sepMatch.groupValues[1]
            val sep = sepMatch.groupValues[2]
            val numbers = siblingNames.mapNotNull { sib ->
                val m = Regex("""^${Regex.escape(prefix)}${Regex.escape(sep)}(\d+)$""").matchEntire(sib)
                m?.groupValues?.get(1)?.toLongOrNull()
            }
            var nextIndex = (numbers.maxOrNull() ?: 0L) + 1L
            while ("$prefix$sep$nextIndex" in siblingNames) {
                nextIndex++
            }
            return "$prefix$sep$nextIndex"
        }

        // 3. Fallback: currentName_${targetHz}hz
        var candidateName = "${currentName}_${targetHz}hz"
        var counter = 1
        while (candidateName in siblingNames) {
            candidateName = "${currentName}_${targetHz}hz_$counter"
            counter++
        }
        return candidateName
    }

    internal fun replaceNodeHeader(nodeText: String, newName: String): String {
        val openHeaderRegex = Regex("""^(\s*)(?:[A-Za-z0-9_.-]+:\s*)?([A-Za-z0-9,._@+\-/#]+)(\s*\{.*)$""", RegexOption.MULTILINE)
        val match = openHeaderRegex.find(nodeText) ?: return nodeText
        val prefix = match.groupValues[1]
        val suffix = match.groupValues[3]
        return nodeText.replaceRange(match.range, "$prefix$newName$suffix")
    }

    private fun patchNodeContent(
        candidate: TimingCandidate,
        nodeText: String,
        targetHz: Int,
        strategy: PatchStrategy,
        customParams: CustomTimingParams? = null
    ): Triple<String, List<String>, List<String>> {
        val refreshProp = findFirstProperty(nodeText, refreshAliases)
            ?: error("目标节点中找不到刷新率属性")

        val replacements = mutableListOf<Replacement>()
        val changes = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        replacements += refreshProp.replaceWith(targetHz.toLong())
        changes += "${refreshProp.name}: ${candidate.currentHz} -> $targetHz"

        when (strategy) {
            PatchStrategy.FRAMERATE_ONLY -> {
                warnings += "仅修改 framerate，不会自动保证 DSI 链路时钟与 porch 满足目标刷新率。"
            }

            PatchStrategy.PIXEL_CLOCK_ONLY -> {
                val clockProp = findFirstProperty(nodeText, pixelClockAliases)
                val oldClock = clockProp?.value ?: candidate.pixelClockHz
                    ?: error("该节点及其父节点均没有可识别的 Pixel Clock 属性，无法使用仅 Pixel Clock 策略")
                val newClock = (oldClock.toDouble() * targetHz / candidate.currentHz).roundToLong()
                validatePixelClock(newClock)
                if (clockProp != null) {
                    replacements += clockProp.replaceWith(newClock)
                    changes += "${clockProp.name}: $oldClock -> $newClock Hz"
                } else {
                    val lastBrace = nodeText.lastIndexOf('}')
                    if (lastBrace != -1) {
                        val indent = nodeText.substringBeforeLast('}').lines().lastOrNull()?.takeWhile { it.isWhitespace() } ?: "\t"
                        val insertText = "    qcom,mdss-dsi-panel-clockrate = <0x${newClock.toString(16)}>;\n$indent"
                        replacements += Replacement(lastBrace..lastBrace - 1, insertText)
                        changes += "qcom,mdss-dsi-panel-clockrate (继承自父节点并新建): $oldClock -> $newClock Hz"
                        warnings += "原时钟定义在父面板节点，已为当前模式子节点生成独立的 panel-clockrate。"
                    }
                }
            }

            PatchStrategy.BALANCED_BLANKING_TIME -> {
                val clockProp = findFirstProperty(nodeText, pixelClockAliases)
                val oldClock = clockProp?.value ?: candidate.pixelClockHz
                    ?: error("该节点及其父节点均没有可识别的 Pixel Clock 属性，无法执行平衡时序计算")
                val vActive = requireValue(candidate.vActive, "vActive")
                val vfp = requireValue(candidate.vFrontPorch, "vFrontPorch")
                val vbp = requireValue(candidate.vBackPorch, "vBackPorch")
                val vsync = requireValue(candidate.vSync, "vSync")

                val fixedVertical = vActive.toLong() + vsync
                val porchVertical = vfp.toLong() + vbp
                val oldVTotal = fixedVertical + porchVertical
                require(fixedVertical > 0 && porchVertical >= 2) {
                    "DTS 垂直时序参数不合法"
                }

                // 这里不假定 qcom panel-clockrate 一定就是裸 Pixel Clock；部分 Qualcomm
                // 设备上它更接近 DSI link clock。只依赖“时钟值与像素吞吐量近似成比例”这一关系。
                // 设 k=clock_new/clock_old，并让 VFP+VBP 行数按 k 缩放，以尽量维持
                // blanking 的时间尺度。结合 refresh ∝ clock/VTotal 可反解 k。
                val ratio = targetHz.toDouble() / candidate.currentHz
                val denominator = oldVTotal - ratio * porchVertical
                require(denominator > 0.0) {
                    "目标刷新率过高，无法在保持垂直 blanking 时间尺度的条件下求解"
                }

                val k = ratio * fixedVertical / denominator
                require(k in 0.50..3.00) {
                    "计算得到的时钟倍率 $k 超出安全计算边界 0.50..3.00"
                }

                val newPorchTotal = max(2, (porchVertical * k).roundToInt())
                var newVfp = max(1, (newPorchTotal.toDouble() * vfp / porchVertical).roundToInt())
                var newVbp = newPorchTotal - newVfp
                if (newVbp < 1) {
                    newVbp = 1
                    newVfp = newPorchTotal - 1
                }

                val newVTotal = fixedVertical + newVfp + newVbp
                val newClock = (
                    oldClock.toDouble() * ratio * newVTotal.toDouble() / oldVTotal.toDouble()
                ).roundToLong()
                validatePixelClock(newClock)

                val vfpProp = findFirstProperty(nodeText, vFrontPorchAliases)
                    ?: error("无法定位 v-front-porch 属性")
                val vbpProp = findFirstProperty(nodeText, vBackPorchAliases)
                    ?: error("无法定位 v-back-porch 属性")

                replacements += vfpProp.replaceWith(newVfp.toLong())
                replacements += vbpProp.replaceWith(newVbp.toLong())
                if (clockProp != null) {
                    replacements += clockProp.replaceWith(newClock)
                    changes += "${clockProp.name}: ${clockProp.value} -> $newClock Hz"
                } else {
                    val lastBrace = nodeText.lastIndexOf('}')
                    if (lastBrace != -1) {
                        val indent = nodeText.substringBeforeLast('}').lines().lastOrNull()?.takeWhile { it.isWhitespace() } ?: "\t"
                        val insertText = "    qcom,mdss-dsi-panel-clockrate = <0x${newClock.toString(16)}>;\n$indent"
                        replacements += Replacement(lastBrace..lastBrace - 1, insertText)
                        changes += "qcom,mdss-dsi-panel-clockrate (继承自父节点并新建): $oldClock -> $newClock Hz"
                        warnings += "原时钟定义在父面板节点，已为当前模式子节点生成独立的 panel-clockrate。"
                    }
                }
                changes += "${vfpProp.name}: $vfp -> $newVfp lines"
                changes += "${vbpProp.name}: $vbp -> $newVbp lines"
                warnings += "水平时序保持不变；垂直 sync 宽度保持不变。"
                if (candidate.hasOpaquePanelTimings) {
                    warnings += "检测到 qcom,mdss-dsi-panel-timings PHY 字节数组；该硬件相关数组不会做通用等比修改。"
                }
            }

            PatchStrategy.CUSTOM -> {
                val clockProp = findFirstProperty(nodeText, pixelClockAliases)
                val oldClock = clockProp?.value ?: candidate.pixelClockHz
                val newClock = customParams?.pixelClockHz ?: oldClock

                if (newClock != null) {
                    validatePixelClock(newClock)
                    if (clockProp != null) {
                        replacements += clockProp.replaceWith(newClock)
                        changes += "${clockProp.name}: ${clockProp.value} -> $newClock Hz (${newClock / 1_000_000.0} MHz)"
                    } else {
                        val lastBrace = nodeText.lastIndexOf('}')
                        if (lastBrace != -1) {
                            val indent = nodeText.substringBeforeLast('}').lines().lastOrNull()?.takeWhile { it.isWhitespace() } ?: "\t"
                            val insertText = "    qcom,mdss-dsi-panel-clockrate = <0x${newClock.toString(16)}>;\n$indent"
                            replacements += Replacement(lastBrace..lastBrace - 1, insertText)
                            changes += "qcom,mdss-dsi-panel-clockrate (自定义新建): $newClock Hz (${newClock / 1_000_000.0} MHz)"
                        }
                    }
                }

                if (customParams?.vFrontPorch != null) {
                    val vfpProp = findFirstProperty(nodeText, vFrontPorchAliases)
                    if (vfpProp != null) {
                        replacements += vfpProp.replaceWith(customParams.vFrontPorch.toLong())
                        changes += "${vfpProp.name}: ${candidate.vFrontPorch ?: vfpProp.value} -> ${customParams.vFrontPorch} lines"
                    } else {
                        warnings += "未在节点中找到 v-front-porch 属性，跳过写入"
                    }
                }

                if (customParams?.vBackPorch != null) {
                    val vbpProp = findFirstProperty(nodeText, vBackPorchAliases)
                    if (vbpProp != null) {
                        replacements += vbpProp.replaceWith(customParams.vBackPorch.toLong())
                        changes += "${vbpProp.name}: ${candidate.vBackPorch ?: vbpProp.value} -> ${customParams.vBackPorch} lines"
                    } else {
                        warnings += "未在节点中找到 v-back-porch 属性，跳过写入"
                    }
                }

                if (customParams?.hFrontPorch != null) {
                    val hfpProp = findFirstProperty(nodeText, hFrontPorchAliases)
                    if (hfpProp != null) {
                        replacements += hfpProp.replaceWith(customParams.hFrontPorch.toLong())
                        changes += "${hfpProp.name}: ${candidate.hFrontPorch ?: hfpProp.value} -> ${customParams.hFrontPorch} px"
                    }
                }

                if (customParams?.hBackPorch != null) {
                    val hbpProp = findFirstProperty(nodeText, hBackPorchAliases)
                    if (hbpProp != null) {
                        replacements += hbpProp.replaceWith(customParams.hBackPorch.toLong())
                        changes += "${hbpProp.name}: ${candidate.hBackPorch ?: hbpProp.value} -> ${customParams.hBackPorch} px"
                    }
                }

                warnings += "已应用自定义时序参数。请确保 Pixel Clock 与消隐参数相互匹配，以避免屏幕失步或黑屏。"
                if (candidate.hasOpaquePanelTimings) {
                    warnings += "检测到 qcom,mdss-dsi-panel-timings PHY 字节数组；该硬件相关数组不会做通用等比修改。"
                }
            }
        }

        val patchedNode = applyReplacements(nodeText, replacements)
        return Triple(patchedNode, changes, warnings)
    }

    private fun validatePixelClock(clock: Long) {
        require(clock in 1_000_000L..4_000_000_000L) {
            "计算得到的 Pixel Clock=$clock Hz 超出 1 MHz..4 GHz 的防呆范围"
        }
    }

    private fun requireValue(value: Int?, name: String): Int {
        return value ?: error("缺少 $name，无法执行平衡时序策略")
    }

    private fun Long.toIntOrNullExact(): Int? {
        if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return toInt()
    }

    private fun valueOf(text: String, aliases: List<String>): Long? {
        return findFirstProperty(text, aliases)?.value
    }

    private fun intValueOf(text: String, aliases: List<String>): Int? {
        return valueOf(text, aliases)?.toIntOrNullExact()
    }

    private data class NodeOpen(
        val name: String,
        val path: String,
        val start: Int
    )

    private data class NodeRange(
        val path: String,
        val start: Int,
        val endExclusive: Int
    )

    private fun parseNodeRanges(text: String): List<NodeRange> {
        val stack = ArrayDeque<NodeOpen>()
        val ranges = mutableListOf<NodeRange>()
        val openRegex = Regex(
            """^\s*(?:[A-Za-z0-9_.-]+:\s*)?([A-Za-z0-9,._@+\-/#]+)\s*\{\s*(?://.*)?$"""
        )

        var lineStart = 0
        val lines = text.split('\n')
        lines.forEach { rawLine ->
            val lineEndExclusive = lineStart + rawLine.length
            val trimmed = rawLine.trim()
            val open = openRegex.matchEntire(rawLine)

            if (open != null) {
                val name = open.groupValues[1]
                val parent = stack.peekLast()?.path
                val path = when {
                    name == "/" -> "/"
                    parent == null || parent == "/" -> "/$name"
                    else -> "$parent/$name"
                }
                stack.addLast(NodeOpen(name, path, lineStart))
            }

            if ((trimmed == "};" || trimmed == "}") && stack.isNotEmpty()) {
                val node = stack.removeLast()
                ranges += NodeRange(
                    path = node.path,
                    start = node.start,
                    endExclusive = lineEndExclusive
                )
            }

            lineStart = lineEndExclusive + 1
        }

        return ranges
    }

    private enum class PropertyFormat {
        CELLS,
        RAW_VALUE
    }

    private data class NumericProperty(
        val name: String,
        val value: Long,
        val cellsRange: IntRange,
        val originalCells: List<String>,
        val absoluteStart: Int,
        val format: PropertyFormat = PropertyFormat.CELLS
    ) {
        fun replaceWith(newValue: Long): Replacement {
            val formatted = when (format) {
                PropertyFormat.CELLS -> {
                    when (originalCells.size) {
                        1 -> {
                            if (originalCells[0].startsWith("0x", ignoreCase = true)) {
                                "0x${newValue.toString(16)}"
                            } else {
                                newValue.toString()
                            }
                        }

                        2 -> {
                            val high = (newValue ushr 32) and 0xffffffffL
                            val low = newValue and 0xffffffffL
                            "0x${high.toString(16)} 0x${low.toString(16)}"
                        }

                        else -> "0x${newValue.toString(16)}"
                    }
                }

                PropertyFormat.RAW_VALUE -> {
                    "<0x${newValue.toString(16)}>"
                }
            }
            return Replacement(cellsRange, formatted)
        }
    }

    private data class Replacement(
        val range: IntRange,
        val value: String
    )

    private fun findAllProperties(text: String, aliases: List<String>): List<NumericProperty> {
        return aliases.flatMap { alias -> findProperties(text, alias) }
            .sortedBy { it.absoluteStart }
    }

    private fun findFirstProperty(text: String, aliases: List<String>): NumericProperty? {
        aliases.forEach { alias ->
            findProperties(text, alias).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun findProperties(text: String, alias: String): List<NumericProperty> {
        val results = mutableListOf<NumericProperty>()

        // 1. 标准 < ... > cell 格式（如 <0x510ff400> 或 <1360000000>）
        val cellRegex = Regex(
            """(?m)^(\s*)${Regex.escape(alias)}\s*=\s*<\s*([^>]+?)\s*>\s*;"""
        )
        cellRegex.findAll(text).forEach { match ->
            val cellGroup = match.groups[2] ?: return@forEach
            val tokens = cellGroup.value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val value = parseCellValue(tokens) ?: return@forEach
            results += NumericProperty(
                name = alias,
                value = value,
                cellsRange = cellGroup.range,
                originalCells = tokens,
                absoluteStart = match.range.first,
                format = PropertyFormat.CELLS
            )
        }

        // 2. DTC 字符串启发式 " ... " 格式（如 1632000000 即 0x61465800 被 DTC 反编译为 "aFX"）
        val stringRegex = Regex(
            """(?m)^(\s*)${Regex.escape(alias)}\s*=\s*"([^"]*?)"\s*;"""
        )
        stringRegex.findAll(text).forEach { match ->
            val strContent = match.groups[2]?.value ?: return@forEach
            val value = parseStringPropertyValue(strContent) ?: return@forEach
            val quoteStart = match.value.indexOf('"')
            val quoteEnd = match.value.lastIndexOf('"')
            if (quoteStart >= 0 && quoteEnd > quoteStart) {
                val absoluteRange = (match.range.first + quoteStart)..(match.range.first + quoteEnd)
                results += NumericProperty(
                    name = alias,
                    value = value,
                    cellsRange = absoluteRange,
                    originalCells = listOf(strContent),
                    absoluteStart = match.range.first,
                    format = PropertyFormat.RAW_VALUE
                )
            }
        }

        // 3. DTC 字节流 [ ... ] 格式（如 [61 46 58 00]）
        val byteRegex = Regex(
            """(?m)^(\s*)${Regex.escape(alias)}\s*=\s*\[\s*([^]]+?)\s*\]\s*;"""
        )
        byteRegex.findAll(text).forEach { match ->
            val byteGroup = match.groups[2] ?: return@forEach
            val tokens = byteGroup.value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val value = parseByteStreamValue(tokens) ?: return@forEach
            val bracketStart = match.value.indexOf('[')
            val bracketEnd = match.value.lastIndexOf(']')
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                val absoluteRange = (match.range.first + bracketStart)..(match.range.first + bracketEnd)
                results += NumericProperty(
                    name = alias,
                    value = value,
                    cellsRange = absoluteRange,
                    originalCells = tokens,
                    absoluteStart = match.range.first,
                    format = PropertyFormat.RAW_VALUE
                )
            }
        }

        return results.sortedBy { it.absoluteStart }
    }

    private fun parseStringPropertyValue(content: String): Long? {
        val bytes = unescapeDtsString(content) + byteArrayOf(0)
        return when (bytes.size) {
            4 -> {
                ((bytes[0].toLong() and 0xffL) shl 24) or
                    ((bytes[1].toLong() and 0xffL) shl 16) or
                    ((bytes[2].toLong() and 0xffL) shl 8) or
                    (bytes[3].toLong() and 0xffL)
            }

            8 -> {
                var v = 0L
                for (i in 0 until 8) {
                    v = (v shl 8) or (bytes[i].toLong() and 0xffL)
                }
                v
            }

            else -> null
        }
    }

    private fun unescapeDtsString(s: String): ByteArray {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    'n' -> { out.add(10); i += 2 }
                    'r' -> { out.add(13); i += 2 }
                    't' -> { out.add(9); i += 2 }
                    '\\' -> { out.add('\\'.code.toByte()); i += 2 }
                    '"' -> { out.add('"'.code.toByte()); i += 2 }
                    '0' -> { out.add(0); i += 2 }
                    'x' -> {
                        if (i + 3 < s.length) {
                            val hex = s.substring(i + 2, i + 4).toIntOrNull(16)
                            if (hex != null) {
                                out.add(hex.toByte())
                                i += 4
                                continue
                            }
                        }
                        out.add(c.code.toByte())
                        i++
                    }
                    else -> { out.add(next.code.toByte()); i += 2 }
                }
            } else {
                out.add(c.code.toByte())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun parseByteStreamValue(tokens: List<String>): Long? {
        val bytes = tokens.map { it.toIntOrNull(16) ?: return null }
        return when (bytes.size) {
            4 -> {
                ((bytes[0].toLong() and 0xffL) shl 24) or
                    ((bytes[1].toLong() and 0xffL) shl 16) or
                    ((bytes[2].toLong() and 0xffL) shl 8) or
                    (bytes[3].toLong() and 0xffL)
            }

            8 -> {
                var v = 0L
                for (i in 0 until 8) {
                    v = (v shl 8) or (bytes[i].toLong() and 0xffL)
                }
                v
            }

            else -> null
        }
    }

    private fun parseCellValue(tokens: List<String>): Long? {
        if (tokens.size !in 1..2) return null
        val cells = tokens.map { token ->
            val clean = token.trim().removeSuffix(",")
            runCatching {
                if (clean.startsWith("0x", ignoreCase = true)) {
                    clean.substring(2).toLong(16)
                } else {
                    clean.toLong(10)
                }
            }.getOrNull() ?: return null
        }

        return when (cells.size) {
            1 -> cells[0]
            2 -> ((cells[0] and 0xffffffffL) shl 32) or (cells[1] and 0xffffffffL)
            else -> null
        }
    }

    private fun applyReplacements(text: String, replacements: List<Replacement>): String {
        val builder = StringBuilder(text)
        replacements
            .distinctBy { it.range }
            .sortedByDescending { it.range.first }
            .forEach { replacement ->
                builder.replace(
                    replacement.range.first,
                    replacement.range.last + 1,
                    replacement.value
                )
            }
        return builder.toString()
    }
}

data class PatchTextResult(
    val text: String,
    val changes: List<String>,
    val warnings: List<String>
)
