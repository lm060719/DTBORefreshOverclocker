package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.model.CapabilityFinding
import io.mo.dtbooverclocker.model.CapabilityKind
import io.mo.dtbooverclocker.model.CapabilityReport
import io.mo.dtbooverclocker.model.CapabilityStatus
import io.mo.dtbooverclocker.model.DtboWorkspace

/**
 * 当前工作区的设备树能力扫描器。
 *
 * 扫描结果只回答“当前 DTBO 中是否发现相关结构”，并不自动意味着该能力可安全修改。
 * Refresh / Resolution / DSC / Charging 使用结构化数据判断；Brightness/HBM、Thermal、Touch
 * 目前采用保守关键字发现，只作为后续模块开发和人工定位的线索。
 */
object CapabilityScanner
{
    private data class Signature(
        val kind: CapabilityKind,
        val tokens: Set<String>,
        val sourceHint: String
    )

    private val signatures = listOf(
        Signature(
            kind = CapabilityKind.BRIGHTNESS_HBM,
            tokens = setOf("brightness", "backlight", "hbm", "bl-level", "max-brightness"),
            sourceHint = "亮度/HBM 常见于显示面板节点、backlight 节点或厂商显示 overlay。"
        ),
        Signature(
            kind = CapabilityKind.THERMAL,
            tokens = setOf("thermal", "cooling", "trip-point", "thermal-sensor"),
            sourceHint = "如果当前 DTBO 未发现 Thermal，相关配置可能位于 vendor_boot、vendor_dlkm 或基础 DTB。"
        ),
        Signature(
            kind = CapabilityKind.TOUCH,
            tokens = setOf("touchscreen", "touchpanel", "goodix", "focaltech", "synaptics", "novatek", "xiaomi-touch"),
            sourceHint = "触控节点可能位于独立 overlay、vendor_boot 或基础 DTB；未发现不代表设备没有触控配置。"
        )
    )

    fun scan(
        workspace: DtboWorkspace,
        progress: (String) -> Unit = {},
        checkCancellation: () -> Unit = {}
    ): CapabilityReport
    {
        val totalStarted = System.nanoTime()
        val documents = workspace.dtsFiles.mapIndexed { index, file ->
            checkCancellation()
            val started = System.nanoTime()
            val text = file.readText()
            val document = DeviceTreeParser.parse(index, text, checkCancellation)
            progress(
                "[CAPABILITY] DTB[$index] 结构解析完成：${document.flatten().size} 节点，" +
                    "${elapsedMs(started)} ms"
            )
            document
        }

        checkCancellation()
        val flattenStarted = System.nanoTime()
        val allNodes = documents.flatMap(DeviceTreeDocument::flatten)
        progress("[CAPABILITY] 节点索引汇总完成：${allNodes.size} 节点，${elapsedMs(flattenStarted)} ms")

        checkCancellation()
        val dscStarted = System.nanoTime()
        val dscTopologies = DscTopologyAnalyzer.analyze(documents)
        progress("[CAPABILITY] DSC 扫描完成：${dscTopologies.size} 个 timing，${elapsedMs(dscStarted)} ms")

        checkCancellation()
        val chargingStarted = System.nanoTime()
        val chargingNodes = ChargingAnalyzer.analyze(documents)
        progress("[CAPABILITY] 充电扫描完成：${chargingNodes.size} 个节点，${elapsedMs(chargingStarted)} ms")
        val editableChargingNodes = chargingNodes.filter { it.editableCount > 0 }

        val refreshCount = workspace.candidates.size
        val resolutionCount = workspace.candidates.count { it.hActive != null && it.vActive != null }

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
            add(
                CapabilityFinding(
                    kind = CapabilityKind.RESOLUTION,
                    status = if (resolutionCount > 0) CapabilityStatus.AVAILABLE else CapabilityStatus.NOT_FOUND,
                    matchCount = resolutionCount,
                    summary = if (resolutionCount > 0) "$resolutionCount 个带完整 width/height 的时序档位" else "当前 DTBO 未发现可分析分辨率档位",
                    examplePaths = workspace.candidates.filter { it.hActive != null && it.vActive != null }.take(3).map { it.nodePath }
                )
            )
            add(
                CapabilityFinding(
                    kind = CapabilityKind.DSC,
                    status = if (dscTopologies.isNotEmpty()) CapabilityStatus.AVAILABLE else CapabilityStatus.NOT_FOUND,
                    matchCount = dscTopologies.size,
                    summary = if (dscTopologies.isNotEmpty()) "${dscTopologies.size} 个 DSC 节点可分析和编辑参数" else "当前 DTBO 未发现可识别 DSC timing",
                    examplePaths = dscTopologies.take(3).map { it.nodePath },
                    sourceHint = "支持 DSC 参数编辑、暂存与导出；不自动同步 PPS/RC 或厂商命令字节。"
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

            val signatureStarted = System.nanoTime()
            val relatedPaths = findRelatedPaths(allNodes, signatures, checkCancellation)
            signatures.forEach { signature ->
                val matchedPaths = relatedPaths[signature.kind].orEmpty()
                add(
                    CapabilityFinding(
                        kind = signature.kind,
                        status = if (matchedPaths.isEmpty()) CapabilityStatus.NOT_FOUND else CapabilityStatus.DETECTED,
                        matchCount = matchedPaths.size,
                        summary = if (matchedPaths.isEmpty()) "当前 DTBO 未发现相关节点/属性" else "发现 ${matchedPaths.size} 处相关节点/属性，暂未开放修改",
                        examplePaths = matchedPaths.take(3),
                        sourceHint = signature.sourceHint
                    )
                )
            }
            progress("[CAPABILITY] 关键字能力扫描完成：${elapsedMs(signatureStarted)} ms")
        }

        val report = CapabilityReport(
            scannedEntryCount = documents.size,
            nodeCount = allNodes.size,
            propertyCount = allNodes.sumOf { it.properties.size },
            findings = findings,
            dscTopologies = dscTopologies,
            chargingNodes = chargingNodes
        )
        progress(
            "[CAPABILITY] 全部扫描完成：${report.nodeCount} 节点 / ${report.propertyCount} 属性，" +
                "总耗时 ${elapsedMs(totalStarted)} ms"
        )
        return report
    }

    private fun findRelatedPaths(
        nodes: List<DeviceTreeNode>,
        signatures: List<Signature>,
        checkCancellation: () -> Unit
    ): Map<CapabilityKind, List<String>>
    {
        val matches = signatures.associate { it.kind to LinkedHashSet<String>() }

        nodes.forEachIndexed { index, node ->
            if (index and 0x7f == 0)
            {
                checkCancellation()
            }

            signatures.forEach { signature ->
                val matched = signature.tokens.any { token ->
                    node.name.contains(token, ignoreCase = true) ||
                        node.path.contains(token, ignoreCase = true) ||
                        node.properties.any { property ->
                            property.name.contains(token, ignoreCase = true)
                        }
                }
                if (matched)
                {
                    matches.getValue(signature.kind) += node.path
                }
            }
        }

        return matches.mapValues { (_, paths) -> paths.toList() }
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L
}
