package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeProperty
import io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec
import io.mo.dtbooverclocker.model.DscIssue
import io.mo.dtbooverclocker.model.DscIssueSeverity
import io.mo.dtbooverclocker.model.DscTopology

/**
 * DSC 只读拓扑分析器。
 *
 * 负责把面板尺寸、DSC slice、BPC/BPP、block prediction、ROI 等属性整理成可验证的
 * DscTopology，并报告不整除、缺字段和 packet 拓扑异常。
 *
 * 这里不生成 DeviceTreeChange，也不修改 PPS、RC range 或厂商 DSI command。
 */
object DscTopologyAnalyzer
{
    private val refreshAliases = listOf(
        "qcom,mdss-dsi-panel-framerate",
        "qcom,mdss-dsi-panel-refresh-rate",
        "panel-framerate",
        "refresh-rate"
    )
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

    fun analyze(documents: List<DeviceTreeDocument>): List<DscTopology>
    {
        return documents.flatMap { document ->
            document.flatten()
                .filter(::looksLikeDscTimingNode)
                .map { node -> analyzeNode(document.entryIndex, node) }
        }
    }

    private fun looksLikeDscTimingNode(node: DeviceTreeNode): Boolean
    {
        val compressionMode = stringValue(node, "qcom,compression-mode")?.lowercase()
        return compressionMode == "dsc" ||
            property(node, "qcom,mdss-dsc-slice-width") != null ||
            property(node, "qcom,mdss-dsc-bit-per-pixel") != null
    }

    private fun analyzeNode(entryIndex: Int, node: DeviceTreeNode): DscTopology
    {
        val width = intValue(node, widthAliases)
        val height = intValue(node, heightAliases)
        val refresh = intValue(node, refreshAliases)
        val compressionMode = stringValue(node, "qcom,compression-mode")
        val version = intValue(node, listOf("qcom,mdss-dsc-version"))
        val scrVersionRaw = property(node, "qcom,mdss-dsc-scr-version")?.rawValue
        val bitsPerComponent = intValue(node, listOf("qcom,mdss-dsc-bit-per-component"))
        val bitsPerPixel = intValue(node, listOf("qcom,mdss-dsc-bit-per-pixel"))
        val sliceWidth = intValue(node, listOf("qcom,mdss-dsc-slice-width"))
        val sliceHeight = intValue(node, listOf("qcom,mdss-dsc-slice-height"))
        val slicePerPacket = intValue(node, listOf("qcom,mdss-dsc-slice-per-pkt"))
        val blockPrediction = property(node, "qcom,mdss-dsc-block-prediction-enable") != null
        val roi = property(node, "qcom,panel-roi-alignment")
            ?.rawValue
            ?.let(DtsNumericValueCodec::decodeCells)

        val horizontalSlices = if (width != null && sliceWidth != null && sliceWidth > 0 && width % sliceWidth == 0)
        {
            width / sliceWidth
        }
        else
        {
            null
        }
        val verticalSlices = if (height != null && sliceHeight != null && sliceHeight > 0 && height % sliceHeight == 0)
        {
            height / sliceHeight
        }
        else
        {
            null
        }
        val slicesPerFrame = if (horizontalSlices != null && verticalSlices != null)
        {
            horizontalSlices * verticalSlices
        }
        else
        {
            null
        }

        val issues = buildList {
            if (compressionMode != null && compressionMode.lowercase() != "dsc")
            {
                add(DscIssue(DscIssueSeverity.WARNING, "节点包含 DSC 参数，但 compression-mode=$compressionMode"))
            }
            if (width == null || height == null)
            {
                add(DscIssue(DscIssueSeverity.ERROR, "缺少可识别的 panel width/height，无法建立完整 DSC 拓扑"))
            }
            if (sliceWidth == null || sliceHeight == null || slicePerPacket == null)
            {
                add(DscIssue(DscIssueSeverity.ERROR, "缺少 slice-width / slice-height / slice-per-pkt 之一"))
            }
            if (width != null && sliceWidth != null && sliceWidth > 0 && width % sliceWidth != 0)
            {
                add(DscIssue(DscIssueSeverity.ERROR, "panel-width=$width 不能被 slice-width=$sliceWidth 整除"))
            }
            if (height != null && sliceHeight != null && sliceHeight > 0 && height % sliceHeight != 0)
            {
                add(DscIssue(DscIssueSeverity.WARNING, "panel-height=$height 不能被 slice-height=$sliceHeight 整除"))
            }
            if (slicePerPacket != null && horizontalSlices != null && slicePerPacket !in 1..horizontalSlices)
            {
                add(DscIssue(DscIssueSeverity.ERROR, "slice-per-pkt=$slicePerPacket 超出横向 slice 数 $horizontalSlices"))
            }
            if (bitsPerComponent != null && bitsPerComponent !in 6..16)
            {
                add(DscIssue(DscIssueSeverity.WARNING, "bits-per-component=$bitsPerComponent 超出常见范围 6..16"))
            }
            if (bitsPerPixel != null && bitsPerPixel !in 6..24)
            {
                add(DscIssue(DscIssueSeverity.WARNING, "bits-per-pixel=$bitsPerPixel 超出常见范围 6..24"))
            }
            if (roi != null)
            {
                if (roi.size != 6)
                {
                    add(DscIssue(DscIssueSeverity.WARNING, "ROI alignment 为 ${roi.size} Cell，当前只识别 6 Cell Qualcomm 全宽模式"))
                }
                else if (width != null)
                {
                    val repeatedFullWidth = roi[0] == width.toLong() &&
                        roi[2] == width.toLong() &&
                        roi[4] == width.toLong() &&
                        roi[1] == roi[3] && roi[3] == roi[5]
                    if (!repeatedFullWidth)
                    {
                        add(DscIssue(DscIssueSeverity.WARNING, "ROI alignment 不是已验证的全宽重复模式"))
                    }
                }
            }
            if (isEmpty())
            {
                add(DscIssue(DscIssueSeverity.INFO, "DSC 拓扑基础约束通过"))
            }
        }

        return DscTopology(
            entryIndex = entryIndex,
            nodePath = node.path,
            refreshHz = refresh,
            panelWidth = width,
            panelHeight = height,
            compressionMode = compressionMode,
            version = version,
            scrVersionRaw = scrVersionRaw,
            bitsPerComponent = bitsPerComponent,
            bitsPerPixel = bitsPerPixel,
            blockPredictionEnabled = blockPrediction,
            sliceWidth = sliceWidth,
            sliceHeight = sliceHeight,
            slicePerPacket = slicePerPacket,
            horizontalSliceCount = horizontalSlices,
            verticalSliceCount = verticalSlices,
            slicesPerFrame = slicesPerFrame,
            roiAlignment = roi,
            issues = issues
        )
    }

    private fun intValue(node: DeviceTreeNode, aliases: List<String>): Int?
    {
        val raw = aliases.firstNotNullOfOrNull { alias -> property(node, alias)?.rawValue }
            ?: return null
        val value = DtsNumericValueCodec.decode(raw) ?: return null
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        {
            return null
        }
        return value.toInt()
    }

    private fun stringValue(node: DeviceTreeNode, name: String): String?
    {
        return property(node, name)
            ?.rawValue
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)
    }

    private fun property(node: DeviceTreeNode, name: String): DeviceTreeProperty?
    {
        return node.properties.firstOrNull { it.name == name }
    }
}
