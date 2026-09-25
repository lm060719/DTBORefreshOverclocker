package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.model.CapabilityFinding
import io.mo.dtbooverclocker.model.CapabilityKind
import io.mo.dtbooverclocker.model.CapabilityReport
import io.mo.dtbooverclocker.model.CapabilityStatus
import io.mo.dtbooverclocker.model.ChargingNode
import io.mo.dtbooverclocker.model.DtboWorkspace

/**
 * 当前工作区的设备树能力扫描器。
 *
 * 扫描结果只回答“当前 DTBO 中是否发现相关结构”，并不自动意味着该能力可安全修改。
 * Refresh / Charging 使用结构化数据判断。
 */
object CapabilityScanner
{
    fun scan(
        workspace: DtboWorkspace,
        progress: (String) -> Unit = {},
        checkCancellation: () -> Unit = {}
    ): CapabilityReport
    {
        val totalStarted = System.nanoTime()
        // Stream one DTB at a time: a 40-entry DTBO parsed all at once (400k properties) does not fit
        // in the default Android heap next to a partition-sized packaging job. Every analyzer is
        // per-document, so results and their order are unchanged.
        var nodeCount = 0
        var propertyCount = 0
        val chargingNodes = mutableListOf<ChargingNode>()
        workspace.dtsFiles.forEachIndexed { index, file ->
            checkCancellation()
            val started = System.nanoTime()
            val document = DeviceTreeParser.parse(index, file.readText(), checkCancellation)
            val nodes = document.flatten()
            nodeCount += nodes.size
            propertyCount += nodes.sumOf { it.properties.size }
            checkCancellation()
            chargingNodes += ChargingAnalyzer.analyze(listOf(document))
            progress("[CAPABILITY] DTB[$index] 扫描完成：${nodes.size} 节点，${elapsedMs(started)} ms")
        }
        progress("[CAPABILITY] 充电 ${chargingNodes.size} 个节点")
        val editableChargingNodes = chargingNodes.filter { it.editableCount > 0 }

        val refreshCount = workspace.candidates.size

        val findings = buildList {
            add(
                CapabilityFinding(
                    kind = CapabilityKind.REFRESH_RATE,
                    status = if (refreshCount > 0) CapabilityStatus.AVAILABLE else CapabilityStatus.NOT_FOUND,
                    matchCount = refreshCount,
                    summary = if (refreshCount > 0) "$refreshCount 个可识别时序档位" else "当前 DTBO 未发现可识别刷新率档位",
                    examplePaths = workspace.candidates.take(3).map { it.nodePath }
                )
            )
            add(CapabilityFinding(
                kind = CapabilityKind.CHARGING,
                status = when {
                    editableChargingNodes.isNotEmpty() -> CapabilityStatus.AVAILABLE
                    chargingNodes.isNotEmpty() -> CapabilityStatus.ANALYSIS_ONLY
                    else -> CapabilityStatus.NOT_FOUND
                },
                matchCount = if (editableChargingNodes.isNotEmpty()) editableChargingNodes.size else chargingNodes.size,
                summary = when {
                    editableChargingNodes.isNotEmpty() -> "${editableChargingNodes.size} 个充电节点可编辑，${editableChargingNodes.sumOf { it.editableCount }} 个参数"
                    chargingNodes.isNotEmpty() -> "发现 ${chargingNodes.size} 个相关节点，未识别到支持的充电参数"
                    else -> "当前 DTBO 未发现充电节点"
                },
                examplePaths = chargingNodes.take(3).map { "DTB ${it.entryIndex}: ${it.nodePath}" },
                sourceHint = "充电配置也可能位于基础 DTB、vendor_boot 或驱动中；支持已识别参数的暂存、撤销和导出验证。"
            ))
        }

        val report = CapabilityReport(
            scannedEntryCount = workspace.dtsFiles.size,
            nodeCount = nodeCount,
            propertyCount = propertyCount,
            findings = findings,
            chargingNodes = chargingNodes
        )
        progress(
            "[CAPABILITY] 全部扫描完成：${report.nodeCount} 节点 / ${report.propertyCount} 属性，" +
                "总耗时 ${elapsedMs(totalStarted)} ms"
        )
        return report
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L
}
