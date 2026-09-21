package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.ResolutionScope
import io.mo.dtbooverclocker.model.TimingCandidate

/**
 * 分辨率功能模块规划器。
 *
 * 当前版本只允许“等比例降分辨率”，并且只修改能从 DTS 中明确证明耦合关系的字段：
 * panel width/height、DSC slice width，以及特征完全匹配的 full-width ROI alignment。
 *
 * 无法证明关系的 porch、PHY、PPS、厂商命令序列和私有属性不会被猜测修改；
 * 规划结果统一作为 EXPORT_ONLY 事务处理，禁止应用内 Root 直刷。
 */
object ResolutionPlanner
{
    private val widthAliases = listOf(
        "qcom,mdss-dsi-panel-width",
        "hactive",
        "h-active"
    )

    private val heightAliases = listOf(
        "qcom,mdss-dsi-panel-height",
        "vactive",
        "v-active"
    )

    private val dscSliceWidthAliases = listOf("qcom,mdss-dsc-slice-width")
    private val dscSliceHeightAliases = listOf("qcom,mdss-dsc-slice-height")
    private val dscSlicePerPacketAliases = listOf("qcom,mdss-dsc-slice-per-pkt")
    private val compressionModeAliases = listOf("qcom,compression-mode")
    private val roiAlignmentAliases = listOf("qcom,panel-roi-alignment")

    data class Plan(
        val operations: List<DeviceTreeChange>,
        val replayedText: String,
        val affectedNodePaths: List<String>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val changes: List<String>,
        val warnings: List<String>,
        val directFlashAllowed: Boolean = false
    )

    fun plan(
        candidate: TimingCandidate,
        targetWidth: Int,
        targetHeight: Int,
        scope: ResolutionScope
    ): Plan
    {
        val sourceWidth = requireNotNull(candidate.hActive) {
            "当前时序节点没有可识别的宽度属性"
        }
        val sourceHeight = requireNotNull(candidate.vActive) {
            "当前时序节点没有可识别的高度属性"
        }

        validateTargetResolution(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )

        val sourceText = candidate.dtsFile.readText()
        val document = DeviceTreeParser.parse(candidate.entryIndex, sourceText)
        val selectedNode = requireNotNull(document.findNode(candidate.nodePath)) {
            "所选 timing 节点已经失效，请重新解析镜像"
        }

        requireNodeGeometry(
            node = selectedNode,
            expectedWidth = sourceWidth,
            expectedHeight = sourceHeight
        )

        val targetNodes = when (scope)
        {
            ResolutionScope.SELECTED_TIMING -> listOf(selectedNode)

            ResolutionScope.MATCHING_GROUP ->
            {
                val parent = requireNotNull(document.findNode(parentPath(selectedNode.path))) {
                    "找不到所选 timing 的父节点"
                }
                parent.children.filter { node ->
                    nodeGeometry(node) == (sourceWidth to sourceHeight)
                }
            }
        }

        require(targetNodes.isNotEmpty()) {
            "没有找到可同步的同组分辨率档位"
        }

        val operations = buildList {
            targetNodes.forEach { node ->
                addAll(
                    buildNodeChanges(
                        entryIndex = candidate.entryIndex,
                        node = node,
                        sourceWidth = sourceWidth,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                )
            }
        }

        require(operations.isNotEmpty()) {
            "分辨率规划没有生成任何设备树操作"
        }

        val replayedText = operations.fold(sourceText) { text, change ->
            DeviceTreeEditor.apply(text, change)
        }

        verifyReplay(
            entryIndex = candidate.entryIndex,
            originalDocument = document,
            replayedText = replayedText,
            operations = operations,
            affectedNodePaths = targetNodes.map { it.path }.toSet(),
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )

        val changes = buildList {
            add("分辨率: ${sourceWidth}×${sourceHeight} → ${targetWidth}×${targetHeight}")
            add("影响档位: ${targetNodes.size} 个")
            if (scope == ResolutionScope.MATCHING_GROUP)
            {
                add("同步范围: 同一 timing 组内全部 ${sourceWidth}×${sourceHeight} 档位")
            }
            else
            {
                add("同步范围: 仅当前 timing 档位")
            }
        }

        val warnings = buildList {
            add("当前分辨率模块仅支持等比例降分辨率；不会猜测或自动重写未知厂商属性。")
            add("DSC 模式仅在 slice 拓扑和 ROI 结构可证明时同步；否则规划器会直接拒绝。")
            add("本版本分辨率修改禁止 Root 直刷，请先导出 dtbo.img / Recovery ZIP / Fastboot 包离线验证。")
            add("节点名称中的 wqhd/fhd 等语义标签暂不重命名，避免破坏潜在引用。")
        }

        return Plan(
            operations = operations,
            replayedText = replayedText,
            affectedNodePaths = targetNodes.map { it.path },
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            changes = changes,
            warnings = warnings
        )
    }

    private fun validateTargetResolution(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    )
    {
        require(targetWidth in 320..sourceWidth) {
            "目标宽度必须在 320..$sourceWidth 之间；Phase 7 暂不支持提高原生分辨率"
        }
        require(targetHeight in 480..sourceHeight) {
            "目标高度必须在 480..$sourceHeight 之间；Phase 7 暂不支持提高原生分辨率"
        }
        require(targetWidth != sourceWidth || targetHeight != sourceHeight) {
            "目标分辨率与当前分辨率相同"
        }

        val sourceCross = sourceWidth.toLong() * targetHeight.toLong()
        val targetCross = targetWidth.toLong() * sourceHeight.toLong()
        require(sourceCross == targetCross) {
            "Phase 7 只允许保持原宽高比的等比例降分辨率，避免未知扫描/裁切行为"
        }
    }

    private fun buildNodeChanges(
        entryIndex: Int,
        node: DeviceTreeNode,
        sourceWidth: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<DeviceTreeChange>
    {
        val widthProperty = requireNotNull(findProperty(node, widthAliases)) {
            "节点 ${node.path} 缺少 panel width"
        }
        val heightProperty = requireNotNull(findProperty(node, heightAliases)) {
            "节点 ${node.path} 缺少 panel height"
        }

        val operations = mutableListOf<DeviceTreeChange>()
        operations += numericSet(
            entryIndex = entryIndex,
            nodePath = node.path,
            property = widthProperty,
            targetValue = targetWidth.toLong()
        )
        operations += numericSet(
            entryIndex = entryIndex,
            nodePath = node.path,
            property = heightProperty,
            targetValue = targetHeight.toLong()
        )

        val compressionMode = findProperty(node, compressionModeAliases)
            ?.rawValue
            ?.trim()
            ?.trim('"')
            ?.lowercase()
        val sliceWidthProperty = findProperty(node, dscSliceWidthAliases)
        val sliceHeightProperty = findProperty(node, dscSliceHeightAliases)
        val slicePerPacketProperty = findProperty(node, dscSlicePerPacketAliases)
        val hasDsc = compressionMode == "dsc" ||
            sliceWidthProperty != null ||
            sliceHeightProperty != null ||
            slicePerPacketProperty != null

        if (hasDsc)
        {
            val sliceWidth = requireNumeric(sliceWidthProperty, "DSC slice width", node.path)
            val sliceHeight = requireNumeric(sliceHeightProperty, "DSC slice height", node.path)
            val slicePerPacket = requireNumeric(slicePerPacketProperty, "DSC slice-per-pkt", node.path)

            require(sliceWidth > 0 && sourceWidth % sliceWidth.toInt() == 0) {
                "节点 ${node.path} 的 DSC slice-width=$sliceWidth 无法整除 panel-width=$sourceWidth"
            }
            val horizontalSliceCount = sourceWidth / sliceWidth.toInt()
            require(horizontalSliceCount in 1..8) {
                "节点 ${node.path} 的 DSC 横向 slice 数量异常：$horizontalSliceCount"
            }
            require(slicePerPacket in 1..horizontalSliceCount.toLong()) {
                "节点 ${node.path} 的 DSC slice-per-pkt=$slicePerPacket 超出 slice 数量"
            }
            require(targetWidth % horizontalSliceCount == 0) {
                "目标宽度 $targetWidth 不能被原 DSC 横向 slice 数 $horizontalSliceCount 整除"
            }
            require(sliceHeight > 0 && targetHeight % sliceHeight.toInt() == 0) {
                "目标高度 $targetHeight 不能被 DSC slice-height=$sliceHeight 整除"
            }

            val newSliceWidth = targetWidth / horizontalSliceCount
            operations += numericSet(
                entryIndex = entryIndex,
                nodePath = node.path,
                property = requireNotNull(sliceWidthProperty),
                targetValue = newSliceWidth.toLong()
            )

            val roiProperty = findProperty(node, roiAlignmentAliases)
            if (roiProperty != null)
            {
                val roi = DtsNumericValueCodec.decodeCells(roiProperty.rawValue)
                    ?: error("节点 ${node.path} 的 panel-roi-alignment 不是可解析 Cell 列表")
                require(roi.size == 6) {
                    "节点 ${node.path} 的 panel-roi-alignment 不是 6 Cell 结构，无法安全推断"
                }

                val fullWidthPattern = roi[0] == sourceWidth.toLong() &&
                    roi[2] == sourceWidth.toLong() &&
                    roi[4] == sourceWidth.toLong() &&
                    roi[1] == roi[3] &&
                    roi[3] == roi[5] &&
                    roi[1] > 0
                require(fullWidthPattern) {
                    "节点 ${node.path} 的 ROI alignment 不符合已验证的全宽模式，拒绝猜测修改"
                }
                require(targetHeight % roi[1].toInt() == 0) {
                    "目标高度 $targetHeight 不能被 ROI 垂直对齐 ${roi[1]} 整除"
                }

                val updatedRoi = roi.toMutableList().apply {
                    this[0] = targetWidth.toLong()
                    this[2] = targetWidth.toLong()
                    this[4] = targetWidth.toLong()
                }
                operations += SetPropertyChange(
                    entryIndex = entryIndex,
                    nodePath = node.path,
                    propertyName = roiProperty.name,
                    oldRawValue = roiProperty.rawValue,
                    newRawValue = DtsNumericValueCodec.encodeCellsLike(
                        roiProperty.rawValue,
                        updatedRoi
                    )
                )
            }
        }

        return operations.distinctBy { change ->
            change.nodePath to change.propertyName
        }
    }

    private fun verifyReplay(
        entryIndex: Int,
        originalDocument: DeviceTreeDocument,
        replayedText: String,
        operations: List<DeviceTreeChange>,
        affectedNodePaths: Set<String>,
        targetWidth: Int,
        targetHeight: Int
    )
    {
        val replayedDocument = DeviceTreeParser.parse(entryIndex, replayedText)
        require(
            originalDocument.flatten().map { it.path }.toSet() ==
                replayedDocument.flatten().map { it.path }.toSet()
        ) {
            "分辨率修改不应改变设备树节点集合"
        }

        affectedNodePaths.forEach { path ->
            val node = requireNotNull(replayedDocument.findNode(path))
            requireNodeGeometry(node, targetWidth, targetHeight)
        }

        val allowedPropertyPaths = operations.mapNotNull { change ->
            change.propertyName?.let { name ->
                "${change.nodePath.trimEnd('/')}/$name"
            }
        }.toSet()

        val originalProperties = propertySnapshot(originalDocument)
        val replayedProperties = propertySnapshot(replayedDocument)
        val allPaths = originalProperties.keys + replayedProperties.keys
        val unexpected = allPaths.filter { path ->
            originalProperties[path] != replayedProperties[path] && path !in allowedPropertyPaths
        }
        require(unexpected.isEmpty()) {
            "分辨率回放产生未声明的属性变化：${unexpected.take(8).joinToString()}"
        }
    }

    private fun requireNodeGeometry(
        node: DeviceTreeNode,
        expectedWidth: Int,
        expectedHeight: Int
    )
    {
        val geometry = nodeGeometry(node)
        require(geometry == (expectedWidth to expectedHeight)) {
            "节点 ${node.path} 分辨率校验失败：期望 ${expectedWidth}×${expectedHeight}，实际 ${geometry.first}×${geometry.second}"
        }
    }

    private fun nodeGeometry(node: DeviceTreeNode): Pair<Int?, Int?>
    {
        val width = findProperty(node, widthAliases)
            ?.rawValue
            ?.let(DtsNumericValueCodec::decode)
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val height = findProperty(node, heightAliases)
            ?.rawValue
            ?.let(DtsNumericValueCodec::decode)
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
        return width to height
    }

    private fun numericSet(
        entryIndex: Int,
        nodePath: String,
        property: DeviceTreeProperty,
        targetValue: Long
    ): DeviceTreeChange
    {
        val oldValue = DtsNumericValueCodec.decode(property.rawValue)
            ?: error("无法解析 ${nodePath}/${property.name}: ${property.rawValue}")
        require(oldValue != targetValue) {
            "属性 ${nodePath}/${property.name} 已经是目标值 $targetValue"
        }

        return SetPropertyChange(
            entryIndex = entryIndex,
            nodePath = nodePath,
            propertyName = property.name,
            oldRawValue = property.rawValue,
            newRawValue = DtsNumericValueCodec.encodeLike(property.rawValue, targetValue)
        )
    }

    private fun requireNumeric(
        property: DeviceTreeProperty?,
        label: String,
        nodePath: String
    ): Long
    {
        val actual = requireNotNull(property) {
            "节点 $nodePath 缺少 $label"
        }
        return DtsNumericValueCodec.decode(actual.rawValue)
            ?: error("节点 $nodePath 的 $label 无法解析：${actual.rawValue}")
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

    private fun propertySnapshot(document: DeviceTreeDocument): Map<String, String?>
    {
        return buildMap {
            document.flatten().forEach { node ->
                node.properties.forEach { property ->
                    put(
                        "${node.path.trimEnd('/')}/${property.name}",
                        property.rawValue?.trim()?.replace(Regex("\\s+"), " ")
                    )
                }
            }
        }
    }

    private fun parentPath(path: String): String
    {
        require(path.startsWith('/') && path != "/") {
            "无效节点路径：$path"
        }
        return path.substringBeforeLast('/').ifEmpty { "/" }
    }
}
