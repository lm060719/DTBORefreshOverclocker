package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.DscTopology
import io.mo.dtbooverclocker.model.DscParameters
import io.mo.dtbooverclocker.model.FeatureModuleKind
import io.mo.dtbooverclocker.model.ModuleStagedChange

/** Plans a single DSC node edit in memory before committing any DTS changes. */
object DscPlanner
{
    data class Plan(val replayedText: String, val transaction: DeviceTreeTransaction)

    fun plan(entryIndex: Int, text: String, nodePath: String, parameters: DscParameters): Plan
    {
        val document = DeviceTreeParser.parse(entryIndex, text)
        val node = requireNotNull(document.findNode(nodePath)) { "DSC 节点已失效，请重新选择" }
        val topology = requireNotNull(DscTopologyAnalyzer.analyze(listOf(document)).find { it.nodePath == nodePath }) {
            "所选节点不是可识别的 DSC 节点"
        }
        validate(topology, parameters)

        val values = linkedMapOf(
            "qcom,mdss-dsc-version" to parameters.version,
            "qcom,mdss-dsc-bit-per-component" to parameters.bitsPerComponent,
            "qcom,mdss-dsc-bit-per-pixel" to parameters.bitsPerPixel,
            "qcom,mdss-dsc-slice-width" to parameters.sliceWidth,
            "qcom,mdss-dsc-slice-height" to parameters.sliceHeight,
            "qcom,mdss-dsc-slice-per-pkt" to parameters.slicePerPacket
        )
        val operations = buildList<DeviceTreeChange> {
            values.forEach { (name, value) ->
                val existing = node.properties.firstOrNull { it.name == name }
                require(value != null || existing == null) { "$name 已存在，不能留空" }
                if (value != null && existing?.rawValue?.let(DtsNumericValueCodec::decode) != value.toLong()) {
                    val raw = "<0x${value.toString(16)}>"
                    add(if (existing == null) AddPropertyChange(entryIndex, nodePath, name, raw)
                        else SetPropertyChange(entryIndex, nodePath, name, existing.rawValue, raw))
                }
            }
            val name = "qcom,mdss-dsc-block-prediction-enable"
            val existing = node.properties.firstOrNull { it.name == name }
            if (parameters.blockPredictionEnabled && existing == null) {
                add(AddPropertyChange(entryIndex, nodePath, name, null))
            } else if (!parameters.blockPredictionEnabled && existing != null) {
                add(DeletePropertyChange(entryIndex, nodePath, name, existing.rawValue))
            }
        }
        require(operations.isNotEmpty()) { "DSC 参数没有变化" }
        val replayed = operations.fold(text, DeviceTreeEditor::apply)
        val staged = ModuleStagedChange(
            module = FeatureModuleKind.DSC,
            entryIndex = entryIndex,
            affectedNodePaths = listOf(nodePath),
            summary = "DSC · ${nodePath.substringAfterLast('/')} · ${operations.size} 项修改",
            changes = operations.map { it.summary },
            warnings = listOf("DSC 修改仅导出验证；PPS、RC range 和厂商 DSI command 不会自动同步。")
        )
        return Plan(replayed, DeviceTreeTransaction.dsc(staged, operations))
    }

    fun validate(topology: DscTopology, parameters: DscParameters)
    {
        require(topology.compressionMode == null || topology.compressionMode.equals("dsc", true)) {
            "当前节点 compression-mode 不是 DSC"
        }
        val width = requireNotNull(topology.panelWidth) { "缺少 panel width" }
        val height = requireNotNull(topology.panelHeight) { "缺少 panel height" }
        require(width > 0 && height > 0) { "面板尺寸必须大于 0" }
        require(parameters.sliceWidth > 0 && width % parameters.sliceWidth == 0) {
            "Slice width 必须大于 0 且能整除面板宽度 $width"
        }
        require(parameters.sliceHeight > 0 && height % parameters.sliceHeight == 0) {
            "Slice height 必须大于 0 且能整除面板高度 $height"
        }
        val horizontalSlices = width / parameters.sliceWidth
        require(parameters.slicePerPacket in 1..horizontalSlices && horizontalSlices % parameters.slicePerPacket == 0) {
            "Slice per packet 必须在 1..$horizontalSlices 内且能整除横向 Slice 数"
        }
        require(parameters.version == null || parameters.version in 1..255) { "DSC version 必须在 0x01..0xff 内" }
        require(parameters.bitsPerComponent == null || parameters.bitsPerComponent in 6..16) { "BPC 必须在 6..16 内" }
        require(parameters.bitsPerPixel == null || parameters.bitsPerPixel in 6..24) { "BPP 必须在 6..24 内" }

    }

}
