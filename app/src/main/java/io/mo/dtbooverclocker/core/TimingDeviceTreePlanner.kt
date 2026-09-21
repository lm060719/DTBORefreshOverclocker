package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.AddPropertyChange
import io.mo.dtbooverclocker.core.devicetree.CloneNodeChange
import io.mo.dtbooverclocker.core.devicetree.DeleteNodeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate

/**
 * 刷新率功能模块到通用 Device Tree Core 的规划层。
 *
 * Phase 6 起正常执行路径不再调用 DtsTimingPatcher.patch() 生成参考文本：
 * 1. TimingParameterCalculator 只负责纯参数计算；
 * 2. 本类把目标参数转换成精确 DeviceTreeChange；
 * 3. DeviceTreeEditor 回放低层操作；
 * 4. 回放后重新解析并验证目标参数、克隆隔离与删除安全条件。
 *
 * DtsTimingPatcher 仍保留 analyzeEntry() 供候选发现，同时旧 patch() 仅用于单元测试回归对照。
 */
object TimingDeviceTreePlanner
{
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

    private val hFrontPorchAliases = listOf(
        "qcom,mdss-dsi-h-front-porch",
        "hfront-porch",
        "h-front-porch"
    )
    private val hBackPorchAliases = listOf(
        "qcom,mdss-dsi-h-back-porch",
        "hback-porch",
        "h-back-porch"
    )
    private val vFrontPorchAliases = listOf(
        "qcom,mdss-dsi-v-front-porch",
        "vfront-porch",
        "v-front-porch"
    )
    private val vBackPorchAliases = listOf(
        "qcom,mdss-dsi-v-back-porch",
        "vback-porch",
        "v-back-porch"
    )
    private val mdpTransferAliases = listOf("qcom,mdss-mdp-transfer-time-us")

    data class Plan(
        val operations: List<DeviceTreeChange>,
        val replayedText: String,
        val targetNodePath: String?,
        val changes: List<String>,
        val warnings: List<String>
    )

    fun plan(
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
        customParams: CustomTimingParams? = null
    ): Plan
    {
        val sourceText = candidate.dtsFile.readText()
        val sourceDocument = DeviceTreeParser.parse(candidate.entryIndex, sourceText)
        val sourceNode = requireNotNull(sourceDocument.findNode(candidate.nodePath)) {
            "DTS 节点路径已失效，请重新解析镜像：${candidate.nodePath}"
        }

        val calculation = if (mode == PatchMode.DELETE_EXISTING)
        {
            null
        }
        else
        {
            TimingParameterCalculator.calculate(
                candidate = candidate,
                targetHz = targetHz,
                strategy = strategy,
                customParams = customParams
            )
        }

        val warnings = mutableListOf<String>()
        calculation?.warnings?.let(warnings::addAll)

        val targetNodePath: String?
        val operations = when (mode)
        {
            PatchMode.OVERWRITE_EXISTING ->
            {
                targetNodePath = candidate.nodePath
                requireNotNull(calculation)
                buildTimingPropertyChanges(
                    entryIndex = candidate.entryIndex,
                    targetNodePath = candidate.nodePath,
                    sourceNode = sourceNode,
                    calculation = calculation,
                    customParams = customParams,
                    warnings = warnings
                )
            }

            PatchMode.APPEND_NEW ->
            {
                requireNotNull(calculation)
                val newNodeName = generateUniqueSiblingNodeName(
                    document = sourceDocument,
                    sourceNode = sourceNode,
                    targetHz = targetHz
                )
                targetNodePath = childPath(parentPath(candidate.nodePath), newNodeName)

                val clone = DeviceTreeEditor.buildCloneNodeChange(
                    entryIndex = candidate.entryIndex,
                    text = sourceText,
                    sourceNodePath = candidate.nodePath,
                    newNodeName = newNodeName,
                    stripRootLabel = true
                )

                buildList {
                    add(clone)
                    addAll(
                        buildTimingPropertyChanges(
                            entryIndex = candidate.entryIndex,
                            targetNodePath = targetNodePath,
                            sourceNode = sourceNode,
                            calculation = calculation,
                            customParams = customParams,
                            warnings = warnings
                        )
                    )
                }
            }

            PatchMode.DELETE_EXISTING ->
            {
                targetNodePath = null
                buildDeleteOperations(
                    entryIndex = candidate.entryIndex,
                    sourceText = sourceText,
                    document = sourceDocument,
                    sourceNode = sourceNode,
                    candidate = candidate,
                    warnings = warnings
                )
            }
        }

        require(operations.isNotEmpty()) {
            "刷新率规划没有生成任何设备树操作，已停止执行"
        }

        val replayedText = replay(sourceText, operations)
        verifyReplay(
            entryIndex = candidate.entryIndex,
            sourceDocument = sourceDocument,
            replayedText = replayedText,
            sourceNode = sourceNode,
            candidate = candidate,
            mode = mode,
            targetNodePath = targetNodePath,
            calculation = calculation,
            customParams = customParams
        )

        val changes = buildList {
            when (mode)
            {
                PatchMode.OVERWRITE_EXISTING ->
                {
                    add("编辑时序节点: ${candidate.nodePath.substringAfterLast('/')} (${candidate.currentHz} -> $targetHz Hz)")
                }
                PatchMode.APPEND_NEW ->
                {
                    add("➕ 新增独立时序节点: ${targetNodePath?.substringAfterLast('/')} ($targetHz Hz)")
                    add("基准模板节点: ${candidate.nodePath.substringAfterLast('/')} (${candidate.currentHz} Hz)")
                    add("保留原有档位: ${candidate.currentHz} Hz 完好保留")
                }
                PatchMode.DELETE_EXISTING ->
                {
                    add("🗑 移除时序节点: ${candidate.nodePath.substringAfterLast('/')} (${candidate.currentHz} Hz)")
                    add("节点路径: ${candidate.nodePath}")
                }
            }
            calculation?.changes?.let(::addAll)
        }

        return Plan(
            operations = operations,
            replayedText = replayedText,
            targetNodePath = targetNodePath,
            changes = changes,
            warnings = warnings.distinct()
        )
    }

    fun replay(
        sourceText: String,
        operations: List<DeviceTreeChange>
    ): String
    {
        return operations.fold(sourceText) { text, change ->
            DeviceTreeEditor.apply(text, change)
        }
    }

    private fun buildTimingPropertyChanges(
        entryIndex: Int,
        targetNodePath: String,
        sourceNode: DeviceTreeNode,
        calculation: TimingParameterCalculator.Result,
        customParams: CustomTimingParams?,
        warnings: MutableList<String>
    ): List<DeviceTreeChange>
    {
        return buildList {
            val refreshProperty = findProperty(sourceNode, refreshAliases)
                ?: error("目标节点中找不到刷新率属性")
            addNumericChangeIfNeeded(
                entryIndex = entryIndex,
                targetNodePath = targetNodePath,
                sourceProperty = refreshProperty,
                propertyName = refreshProperty.name,
                targetValue = calculation.refreshHz.toLong(),
                addWhenMissing = false
            )?.let(::add)

            calculation.pixelClockHz?.let { clock ->
                val clockProperty = findProperty(sourceNode, pixelClockAliases)
                addNumericChangeIfNeeded(
                    entryIndex = entryIndex,
                    targetNodePath = targetNodePath,
                    sourceProperty = clockProperty,
                    propertyName = clockProperty?.name ?: "qcom,mdss-dsi-panel-clockrate",
                    targetValue = clock,
                    addWhenMissing = true
                )?.let(::add)

                if (clockProperty == null)
                {
                    warnings += if (customParams?.pixelClockHz != null)
                    {
                        "原时钟未定义在当前时序节点，已为当前模式生成独立的 panel-clockrate。"
                    }
                    else
                    {
                        "原时钟定义在父面板节点，已为当前模式子节点生成独立的 panel-clockrate。"
                    }
                }
            }

            appendOptionalPorchChange(
                output = this,
                entryIndex = entryIndex,
                targetNodePath = targetNodePath,
                sourceNode = sourceNode,
                aliases = vFrontPorchAliases,
                targetValue = calculation.vFrontPorch,
                required = calculation.effectiveStrategy == PatchStrategy.BALANCED_BLANKING_TIME,
                missingWarning = "未在节点中找到 v-front-porch 属性，跳过写入",
                warnings = warnings
            )
            appendOptionalPorchChange(
                output = this,
                entryIndex = entryIndex,
                targetNodePath = targetNodePath,
                sourceNode = sourceNode,
                aliases = vBackPorchAliases,
                targetValue = calculation.vBackPorch,
                required = calculation.effectiveStrategy == PatchStrategy.BALANCED_BLANKING_TIME,
                missingWarning = "未在节点中找到 v-back-porch 属性，跳过写入",
                warnings = warnings
            )
            appendOptionalPorchChange(
                output = this,
                entryIndex = entryIndex,
                targetNodePath = targetNodePath,
                sourceNode = sourceNode,
                aliases = hFrontPorchAliases,
                targetValue = calculation.hFrontPorch,
                required = false,
                missingWarning = "未在节点中找到 h-front-porch 属性，跳过写入",
                warnings = warnings
            )
            appendOptionalPorchChange(
                output = this,
                entryIndex = entryIndex,
                targetNodePath = targetNodePath,
                sourceNode = sourceNode,
                aliases = hBackPorchAliases,
                targetValue = calculation.hBackPorch,
                required = false,
                missingWarning = "未在节点中找到 h-back-porch 属性，跳过写入",
                warnings = warnings
            )

            calculation.mdpTransferTimeUs?.let { transfer ->
                val transferProperty = findProperty(sourceNode, mdpTransferAliases)
                    ?: error("候选档位包含 MDP 传输预算，但目标节点中无法定位 qcom,mdss-mdp-transfer-time-us")
                addNumericChangeIfNeeded(
                    entryIndex = entryIndex,
                    targetNodePath = targetNodePath,
                    sourceProperty = transferProperty,
                    propertyName = transferProperty.name,
                    targetValue = transfer,
                    addWhenMissing = false
                )?.let(::add)
            }
        }
    }

    private fun appendOptionalPorchChange(
        output: MutableList<DeviceTreeChange>,
        entryIndex: Int,
        targetNodePath: String,
        sourceNode: DeviceTreeNode,
        aliases: List<String>,
        targetValue: Int?,
        required: Boolean,
        missingWarning: String,
        warnings: MutableList<String>
    )
    {
        if (targetValue == null)
        {
            return
        }

        val property = findProperty(sourceNode, aliases)
        if (property == null)
        {
            if (required)
            {
                error("无法定位 ${aliases.first()} 属性")
            }
            warnings += missingWarning
            return
        }

        addNumericChangeIfNeeded(
            entryIndex = entryIndex,
            targetNodePath = targetNodePath,
            sourceProperty = property,
            propertyName = property.name,
            targetValue = targetValue.toLong(),
            addWhenMissing = false
        )?.let(output::add)
    }

    private fun addNumericChangeIfNeeded(
        entryIndex: Int,
        targetNodePath: String,
        sourceProperty: DeviceTreeProperty?,
        propertyName: String,
        targetValue: Long,
        addWhenMissing: Boolean
    ): DeviceTreeChange?
    {
        if (sourceProperty == null)
        {
            if (!addWhenMissing)
            {
                return null
            }

            return AddPropertyChange(
                entryIndex = entryIndex,
                nodePath = targetNodePath,
                propertyName = propertyName,
                newRawValue = DtsNumericValueCodec.encodeLike(null, targetValue)
            )
        }

        val oldValue = DtsNumericValueCodec.decode(sourceProperty.rawValue)
            ?: error("无法解析数值属性 ${sourceProperty.name}: ${sourceProperty.rawValue}")
        if (oldValue == targetValue)
        {
            return null
        }

        return SetPropertyChange(
            entryIndex = entryIndex,
            nodePath = targetNodePath,
            propertyName = sourceProperty.name,
            oldRawValue = sourceProperty.rawValue,
            newRawValue = DtsNumericValueCodec.encodeLike(sourceProperty.rawValue, targetValue)
        )
    }

    private fun buildDeleteOperations(
        entryIndex: Int,
        sourceText: String,
        document: DeviceTreeDocument,
        sourceNode: DeviceTreeNode,
        candidate: TimingCandidate,
        warnings: MutableList<String>
    ): List<DeviceTreeChange>
    {
        val parentPath = parentPath(candidate.nodePath)
        val parent = requireNotNull(document.findNode(parentPath)) {
            "找不到时序节点父级：$parentPath"
        }

        val timingSiblings = parent.children.filter { node ->
            findProperty(node, refreshAliases) != null
        }
        val remainingSiblings = timingSiblings.filter { it.path != candidate.nodePath }
        require(remainingSiblings.isNotEmpty()) {
            "该屏幕面板仅包含一个可识别时序档位节点，删除会导致屏幕无可用时序无法开机，禁止删除。"
        }

        val operations = mutableListOf<DeviceTreeChange>()
        val deletedLabel = sourceNode.label
        val deletedName = sourceNode.name
        val replacementNode = remainingSiblings.first()
        val replacementRef = replacementNode.label?.let { "&$it" }
            ?: "&{${replacementNode.path}}"

        document.flatten().forEach { node ->
            node.properties
                .filter { it.name == "native-mode" }
                .forEach { property ->
                    val raw = property.rawValue.orEmpty()
                    val referencesDeleted =
                        (deletedLabel != null && raw.contains("&$deletedLabel")) ||
                            raw.contains("&{${candidate.nodePath}}") ||
                            raw.contains("&$deletedName")

                    if (referencesDeleted)
                    {
                        operations += SetPropertyChange(
                            entryIndex = entryIndex,
                            nodePath = node.path,
                            propertyName = property.name,
                            oldRawValue = property.rawValue,
                            newRawValue = "<$replacementRef>"
                        )
                        warnings += "已自动修正 native-mode 指向剩余的时序档位 $replacementRef。"
                    }
                }
        }

        operations += DeviceTreeEditor.buildDeleteNodeChange(
            entryIndex = entryIndex,
            text = sourceText,
            nodePath = candidate.nodePath
        )

        return operations
    }

    private fun verifyReplay(
        entryIndex: Int,
        sourceDocument: DeviceTreeDocument,
        replayedText: String,
        sourceNode: DeviceTreeNode,
        candidate: TimingCandidate,
        mode: PatchMode,
        targetNodePath: String?,
        calculation: TimingParameterCalculator.Result?,
        customParams: CustomTimingParams?
    )
    {
        val replayedDocument = DeviceTreeParser.parse(entryIndex, replayedText)

        when (mode)
        {
            PatchMode.OVERWRITE_EXISTING ->
            {
                val targetNode = requireNotNull(replayedDocument.findNode(candidate.nodePath)) {
                    "通用操作回放后找不到原时序节点"
                }
                verifyCalculatedProperties(targetNode, calculation, customParams)
            }

            PatchMode.APPEND_NEW ->
            {
                val newPath = requireNotNull(targetNodePath)
                val targetNode = requireNotNull(replayedDocument.findNode(newPath)) {
                    "通用操作回放后找不到新增时序节点：$newPath"
                }
                verifyCalculatedProperties(targetNode, calculation, customParams)

                val originalAfter = requireNotNull(replayedDocument.findNode(candidate.nodePath)) {
                    "新增档位后原模板节点意外消失"
                }
                require(nodeSnapshot(sourceNode) == nodeSnapshot(originalAfter)) {
                    "新增档位时原模板节点发生了非预期变化"
                }
            }

            PatchMode.DELETE_EXISTING ->
            {
                require(replayedDocument.findNode(candidate.nodePath) == null) {
                    "删除操作回放后目标时序节点仍然存在"
                }
                val parent = requireNotNull(replayedDocument.findNode(parentPath(candidate.nodePath)))
                require(parent.children.any { findProperty(it, refreshAliases) != null }) {
                    "删除操作回放后父级不存在可用时序档位"
                }
            }
        }

        // 结构性保护：覆盖模式不得改变节点集合；新增模式只允许增加目标子树；删除模式只允许删除目标子树。
        val beforePaths = sourceDocument.flatten().map { it.path }.toSet()
        val afterPaths = replayedDocument.flatten().map { it.path }.toSet()
        when (mode)
        {
            PatchMode.OVERWRITE_EXISTING -> require(beforePaths == afterPaths) {
                "覆盖档位意外改变了设备树节点集合"
            }
            PatchMode.APPEND_NEW ->
            {
                val newPath = requireNotNull(targetNodePath)
                require(beforePaths.all { it in afterPaths }) {
                    "新增档位意外删除了原设备树节点"
                }
                require((afterPaths - beforePaths).all { path ->
                    path == newPath || path.startsWith("${newPath.trimEnd('/')}/")
                }) {
                    "新增档位产生了目标子树之外的新节点"
                }
            }
            PatchMode.DELETE_EXISTING -> require((beforePaths - afterPaths).all { path ->
                path == candidate.nodePath || path.startsWith("${candidate.nodePath.trimEnd('/')}/")
            }) {
                "删除档位移除了目标子树之外的节点"
            }
        }
    }

    private fun verifyCalculatedProperties(
        targetNode: DeviceTreeNode,
        calculation: TimingParameterCalculator.Result?,
        customParams: CustomTimingParams?
    )
    {
        val result = requireNotNull(calculation)
        requireNumericValue(targetNode, refreshAliases, result.refreshHz.toLong(), "刷新率")

        result.pixelClockHz?.let { expected ->
            requireNumericValue(targetNode, pixelClockAliases, expected, "Pixel Clock")
        }
        result.vFrontPorch?.let { expected ->
            if (findProperty(targetNode, vFrontPorchAliases) != null || result.effectiveStrategy == PatchStrategy.BALANCED_BLANKING_TIME)
            {
                requireNumericValue(targetNode, vFrontPorchAliases, expected.toLong(), "VFP")
            }
            }
        }
        result.vBackPorch?.let { expected ->
            if (findProperty(targetNode, vBackPorchAliases) != null || result.effectiveStrategy == PatchStrategy.BALANCED_BLANKING_TIME)
            {
                requireNumericValue(targetNode, vBackPorchAliases, expected.toLong(), "VBP")
            }
        }
        result.hFrontPorch?.let { expected ->
            if (customParams?.hFrontPorch != null && findProperty(targetNode, hFrontPorchAliases) != null)
            {
                requireNumericValue(targetNode, hFrontPorchAliases, expected.toLong(), "HFP")
            }
        }
        result.hBackPorch?.let { expected ->
            if (customParams?.hBackPorch != null && findProperty(targetNode, hBackPorchAliases) != null)
            {
                requireNumericValue(targetNode, hBackPorchAliases, expected.toLong(), "HBP")
            }
        }
        result.mdpTransferTimeUs?.let { expected ->
            requireNumericValue(targetNode, mdpTransferAliases, expected, "MDP Transfer")
        }
    }

    private fun requireNumericValue(
        node: DeviceTreeNode,
        aliases: List<String>,
        expected: Long,
        label: String
    )
    {
        val property = findProperty(node, aliases)
            ?: error("回放校验无法定位 $label 属性")
        val actual = DtsNumericValueCodec.decode(property.rawValue)
            ?: error("回放校验无法解析 $label: ${property.rawValue}")
        require(actual == expected) {
            "$label 回放校验失败：期望 $expected，实际 $actual"
        }
    }

    private fun generateUniqueSiblingNodeName(
        document: DeviceTreeDocument,
        sourceNode: DeviceTreeNode,
        targetHz: Int
    ): String
    {
        val parent = requireNotNull(document.findNode(parentPath(sourceNode.path))) {
            "找不到模板节点父级"
        }
        val currentName = sourceNode.name
        val siblingNames = parent.children.map { it.name }.toSet()

        val normalName = Regex("""^(.*_normal_)\d+hz_index_\d+$""").matchEntire(currentName)
        if (normalName != null)
        {
            val next = siblingNames.mapNotNull { name ->
                Regex("""_index_(\d+)$""").find(name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }.maxOrNull()?.plus(1) ?: 0
            return "${normalName.groupValues[1]}${targetHz}hz_index_$next"
        }

        val atMatch = Regex("""^([A-Za-z0-9,._\-/#]+)@([0-9a-fA-F]+)$""").matchEntire(currentName)
        if (atMatch != null)
        {
            val prefix = atMatch.groupValues[1]
            val numbers = siblingNames.mapNotNull { sibling ->
                val match = Regex("""^${Regex.escape(prefix)}@([0-9a-fA-F]+)$""").matchEntire(sibling)
                    ?: return@mapNotNull null
                match.groupValues[1].toLongOrNull(10)
                    ?: match.groupValues[1].toLongOrNull(16)
            }
            var nextIndex = (numbers.maxOrNull() ?: 0L) + 1L
            while ("$prefix@$nextIndex" in siblingNames)
            {
                nextIndex++
            }
            return "$prefix@$nextIndex"
        }

        val separatorMatch = Regex("""^([A-Za-z0-9,._@\-/#]+)([-_])(\d+)$""").matchEntire(currentName)
        if (separatorMatch != null)
        {
            val prefix = separatorMatch.groupValues[1]
            val separator = separatorMatch.groupValues[2]
            val numbers = siblingNames.mapNotNull { sibling ->
                Regex("""^${Regex.escape(prefix)}${Regex.escape(separator)}(\d+)$""")
                    .matchEntire(sibling)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            }
            var nextIndex = (numbers.maxOrNull() ?: 0L) + 1L
            while ("$prefix$separator$nextIndex" in siblingNames)
            {
                nextIndex++
            }
            return "$prefix$separator$nextIndex"
        }

        var candidateName = "${currentName}_${targetHz}hz"
        var counter = 1
        while (candidateName in siblingNames)
        {
            candidateName = "${currentName}_${targetHz}hz_$counter"
            counter++
        }
        return candidateName
    }

    private fun findProperty(
        node: DeviceTreeNode,
        aliases: List<String>
    ): DeviceTreeProperty?
    {
        return aliases.firstNotNullOfOrNull { alias ->
            node.properties.firstOrNull { it.name == alias }
        }
    }

    private data class NodeSnapshot(
        val label: String?,
        val properties: Map<String, String?>,
        val childNames: List<String>
    )

    private fun nodeSnapshot(node: DeviceTreeNode): NodeSnapshot
    {
        return NodeSnapshot(
            label = node.label,
            properties = node.properties.associate { it.name to normalizeRaw(it.rawValue) },
            childNames = node.children.map { it.name }
        )
    }

    private fun normalizeRaw(raw: String?): String?
    {
        return raw
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parentPath(path: String): String
    {
        require(path.startsWith('/') && path != "/") {
            "无效节点路径：$path"
        }
        return path.substringBeforeLast('/').ifEmpty { "/" }
    }

    private fun childPath(parentPath: String, childName: String): String
    {
        return if (parentPath == "/")
        {
            "/$childName"
        }
        else
        {
            "${parentPath.trimEnd('/')}/$childName"
        }
    }
}
