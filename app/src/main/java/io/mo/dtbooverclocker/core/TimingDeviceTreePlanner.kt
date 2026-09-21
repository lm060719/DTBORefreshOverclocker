package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.AddPropertyChange
import io.mo.dtbooverclocker.core.devicetree.CloneNodeChange
import io.mo.dtbooverclocker.core.devicetree.DeleteNodeChange
import io.mo.dtbooverclocker.core.devicetree.DeletePropertyChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeDocument
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate

/**
 * 将刷新率模块的旧文本修补结果桥接为通用 DeviceTreeChange。
 *
 * 当前阶段故意保留 DtsTimingPatcher 作为“计算与回归基准”，避免同时改动时序公式和
 * 设备树执行链路。真正写入 DTS 的动作由 DeviceTreeEditor 执行，后续分辨率 / DSC 等
 * 功能模块可以复用同一套 Change -> Diff -> Verify -> Package 流程。
 */
object TimingDeviceTreePlanner
{
    data class Plan(
        val operations: List<DeviceTreeChange>,
        val expectedText: String,
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

        // 旧实现仅作为时序计算和语义结果的参考，不再负责最终写入。
        val reference = DtsTimingPatcher.patch(
            candidate = candidate,
            targetHz = targetHz,
            strategy = strategy,
            mode = mode,
            customParams = customParams
        )

        val sourceDocument = DeviceTreeParser.parse(candidate.entryIndex, sourceText)
        val expectedDocument = DeviceTreeParser.parse(candidate.entryIndex, reference.text)

        val operations = when (mode)
        {
            PatchMode.OVERWRITE_EXISTING -> planOverwrite(
                entryIndex = candidate.entryIndex,
                sourceDocument = sourceDocument,
                expectedDocument = expectedDocument,
                nodePath = candidate.nodePath
            )

            PatchMode.APPEND_NEW -> planAppend(
                entryIndex = candidate.entryIndex,
                sourceText = sourceText,
                sourceDocument = sourceDocument,
                expectedDocument = expectedDocument,
                sourceNodePath = candidate.nodePath
            )

            PatchMode.DELETE_EXISTING -> planDelete(
                entryIndex = candidate.entryIndex,
                sourceText = sourceText,
                sourceDocument = sourceDocument,
                expectedDocument = expectedDocument,
                deletedNodePath = candidate.nodePath
            )
        }

        require(operations.isNotEmpty()) {
            "刷新率规划没有生成任何设备树操作，已停止执行"
        }

        val replayedText = replay(sourceText, operations)
        assertSemanticEquivalent(
            entryIndex = candidate.entryIndex,
            expectedText = reference.text,
            actualText = replayedText
        )

        val targetNodePath = when (mode)
        {
            PatchMode.OVERWRITE_EXISTING -> candidate.nodePath
            PatchMode.APPEND_NEW -> operations
                .filterIsInstance<CloneNodeChange>()
                .singleOrNull()
                ?.nodePath
            PatchMode.DELETE_EXISTING -> null
        }

        return Plan(
            operations = operations,
            expectedText = reference.text,
            replayedText = replayedText,
            targetNodePath = targetNodePath,
            changes = reference.changes,
            warnings = reference.warnings
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

    private fun planOverwrite(
        entryIndex: Int,
        sourceDocument: DeviceTreeDocument,
        expectedDocument: DeviceTreeDocument,
        nodePath: String
    ): List<DeviceTreeChange>
    {
        require(sourceDocument.flatten().map { it.path }.toSet() ==
            expectedDocument.flatten().map { it.path }.toSet()) {
            "覆盖时序模式意外改变了节点集合，已停止迁移执行"
        }

        requireNotNull(sourceDocument.findNode(nodePath)) {
            "原始时序节点不存在：$nodePath"
        }
        requireNotNull(expectedDocument.findNode(nodePath)) {
            "参考结果中的时序节点不存在：$nodePath"
        }

        val operations = subtreePropertyDelta(
            entryIndex = entryIndex,
            sourceDocument = sourceDocument,
            targetDocument = expectedDocument,
            sourceRootPath = nodePath,
            targetRootPath = nodePath
        )

        requireOnlyDeclaredNodesChanged(
            sourceDocument = sourceDocument,
            expectedDocument = expectedDocument,
            allowedNodePaths = setOf(nodePath)
        )

        return operations
    }

    private fun planAppend(
        entryIndex: Int,
        sourceText: String,
        sourceDocument: DeviceTreeDocument,
        expectedDocument: DeviceTreeDocument,
        sourceNodePath: String
    ): List<DeviceTreeChange>
    {
        val sourcePaths = sourceDocument.flatten().map { it.path }.toSet()
        val expectedPaths = expectedDocument.flatten().map { it.path }.toSet()
        val addedPaths = expectedPaths - sourcePaths
        val addedRootPaths = addedPaths.filter { path ->
            parentPath(path) in sourcePaths
        }

        require(addedRootPaths.size == 1) {
            "新增档位参考结果应只增加 1 个顶层节点，实际增加 ${addedRootPaths.size} 个：${addedRootPaths.joinToString()}"
        }

        val newNodePath = addedRootPaths.single()
        require(addedPaths.all { path ->
            path == newNodePath || path.startsWith("${newNodePath.trimEnd('/')}/")
        }) {
            "新增档位参考结果包含目标子树之外的新节点：${addedPaths.joinToString()}"
        }

        val sourceParent = parentPath(sourceNodePath)
        require(parentPath(newNodePath) == sourceParent) {
            "新增档位必须与模板节点同级：模板=$sourceNodePath，新节点=$newNodePath"
        }

        requireNotNull(sourceDocument.findNode(sourceNodePath)) {
            "原始时序节点不存在：$sourceNodePath"
        }
        val expectedNode = requireNotNull(expectedDocument.findNode(newNodePath)) {
            "参考结果中的新增时序节点不存在：$newNodePath"
        }

        val clone = DeviceTreeEditor.buildCloneNodeChange(
            entryIndex = entryIndex,
            text = sourceText,
            sourceNodePath = sourceNodePath,
            newNodeName = expectedNode.name
        )

        val operations = mutableListOf<DeviceTreeChange>()
        operations += clone
        operations += subtreePropertyDelta(
            entryIndex = entryIndex,
            sourceDocument = sourceDocument,
            targetDocument = expectedDocument,
            sourceRootPath = sourceNodePath,
            targetRootPath = newNodePath
        )

        requireOnlyDeclaredNodesChanged(
            sourceDocument = sourceDocument,
            expectedDocument = expectedDocument,
            allowedNodePaths = setOf(newNodePath),
            ignoreAddedNodes = true
        )

        return operations
    }

    private fun planDelete(
        entryIndex: Int,
        sourceText: String,
        sourceDocument: DeviceTreeDocument,
        expectedDocument: DeviceTreeDocument,
        deletedNodePath: String
    ): List<DeviceTreeChange>
    {
        require(sourceDocument.findNode(deletedNodePath) != null) {
            "待删除时序节点不存在：$deletedNodePath"
        }
        require(expectedDocument.findNode(deletedNodePath) == null) {
            "参考结果仍包含待删除时序节点：$deletedNodePath"
        }

        val sourcePaths = sourceDocument.flatten().map { it.path }.toSet()
        val expectedPaths = expectedDocument.flatten().map { it.path }.toSet()
        val removedPaths = sourcePaths - expectedPaths

        require(removedPaths.isNotEmpty() && removedPaths.all { path ->
            path == deletedNodePath || path.startsWith("${deletedNodePath.trimEnd('/')}/")
        }) {
            "删除档位参考结果意外移除了目标子树之外的节点：${removedPaths.joinToString()}"
        }

        val operations = mutableListOf<DeviceTreeChange>()

        // 删除节点前先同步 native-mode 等外围引用变化，确保通用操作顺序可独立回放。
        (sourcePaths intersect expectedPaths)
            .sorted()
            .forEach { path ->
                val sourceNode = requireNotNull(sourceDocument.findNode(path))
                val expectedNode = requireNotNull(expectedDocument.findNode(path))
                operations += propertyDelta(
                    entryIndex = entryIndex,
                    sourceNode = sourceNode,
                    targetNode = expectedNode,
                    targetNodePath = path
                )
            }

        operations += DeviceTreeEditor.buildDeleteNodeChange(
            entryIndex = entryIndex,
            text = sourceText,
            nodePath = deletedNodePath
        )

        return operations
    }

    private fun subtreePropertyDelta(
        entryIndex: Int,
        sourceDocument: DeviceTreeDocument,
        targetDocument: DeviceTreeDocument,
        sourceRootPath: String,
        targetRootPath: String
    ): List<DeviceTreeChange>
    {
        val sourceNodes = subtreeByRelativePath(sourceDocument, sourceRootPath)
        val targetNodes = subtreeByRelativePath(targetDocument, targetRootPath)

        require(sourceNodes.keys == targetNodes.keys) {
            "时序节点子树结构发生了非预期变化：source=${sourceNodes.keys} target=${targetNodes.keys}"
        }

        return buildList {
            sourceNodes.keys.sorted().forEach { relativePath ->
                val sourceNode = requireNotNull(sourceNodes[relativePath])
                val targetNode = requireNotNull(targetNodes[relativePath])
                val targetPath = joinRelativePath(targetRootPath, relativePath)

                addAll(
                    propertyDelta(
                        entryIndex = entryIndex,
                        sourceNode = sourceNode,
                        targetNode = targetNode,
                        targetNodePath = targetPath
                    )
                )
            }
        }
    }

    private fun subtreeByRelativePath(
        document: DeviceTreeDocument,
        rootPath: String
    ): Map<String, DeviceTreeNode>
    {
        val root = requireNotNull(document.findNode(rootPath)) {
            "设备树节点不存在：$rootPath"
        }

        val prefix = rootPath.trimEnd('/')
        return document.flatten()
            .asSequence()
            .filter { node ->
                node.path == root.path || node.path.startsWith("$prefix/")
            }
            .associateBy { node ->
                if (node.path == root.path) "" else node.path.removePrefix(prefix)
            }
    }

    private fun joinRelativePath(rootPath: String, relativePath: String): String
    {
        return if (relativePath.isEmpty())
        {
            rootPath
        }
        else
        {
            rootPath.trimEnd('/') + relativePath
        }
    }

    private fun propertyDelta(
        entryIndex: Int,
        sourceNode: DeviceTreeNode,
        targetNode: DeviceTreeNode,
        targetNodePath: String
    ): List<DeviceTreeChange>
    {
        val sourceProperties = sourceNode.properties.associateBy { it.name }
        val targetProperties = targetNode.properties.associateBy { it.name }
        val names = (sourceProperties.keys + targetProperties.keys).toSortedSet()

        return buildList {
            names.forEach { name ->
                val source = sourceProperties[name]
                val target = targetProperties[name]

                when
                {
                    source == null && target != null -> add(
                        AddPropertyChange(
                            entryIndex = entryIndex,
                            nodePath = targetNodePath,
                            propertyName = name,
                            newRawValue = target.rawValue
                        )
                    )

                    source != null && target == null -> add(
                        DeletePropertyChange(
                            entryIndex = entryIndex,
                            nodePath = targetNodePath,
                            propertyName = name,
                            oldRawValue = source.rawValue
                        )
                    )

                    source != null && target != null &&
                        normalizeRaw(source.rawValue) != normalizeRaw(target.rawValue) -> add(
                        SetPropertyChange(
                            entryIndex = entryIndex,
                            nodePath = targetNodePath,
                            propertyName = name,
                            oldRawValue = source.rawValue,
                            newRawValue = target.rawValue
                        )
                    )
                }
            }
        }
    }

    private fun requireOnlyDeclaredNodesChanged(
        sourceDocument: DeviceTreeDocument,
        expectedDocument: DeviceTreeDocument,
        allowedNodePaths: Set<String>,
        ignoreAddedNodes: Boolean = false
    )
    {
        val source = snapshot(sourceDocument)
        val expected = snapshot(expectedDocument)
        val paths = (source.keys + expected.keys).toSortedSet()

        val unexpected = paths.filter { path ->
            if (ignoreAddedNodes && path !in source && path in expected)
            {
                return@filter false
            }

            val allowed = allowedNodePaths.any { allowedPath ->
                path == allowedPath || path.startsWith("${allowedPath.trimEnd('/')}/")
            }
            !allowed && source[path] != expected[path]
        }

        require(unexpected.isEmpty()) {
            "时序参考结果包含未声明的外围设备树变化：${unexpected.take(8).joinToString()}"
        }
    }

    private fun assertSemanticEquivalent(
        entryIndex: Int,
        expectedText: String,
        actualText: String
    )
    {
        val expected = snapshot(DeviceTreeParser.parse(entryIndex, expectedText))
        val actual = snapshot(DeviceTreeParser.parse(entryIndex, actualText))

        require(expected == actual) {
            val differingPaths = (expected.keys + actual.keys)
                .toSortedSet()
                .filter { expected[it] != actual[it] }
                .take(8)
            "通用设备树操作回放结果与旧时序算法语义不一致：${differingPaths.joinToString()}"
        }
    }

    private data class NodeSnapshot(
        val label: String?,
        val properties: Map<String, String?>
    )

    private fun snapshot(document: DeviceTreeDocument): Map<String, NodeSnapshot>
    {
        return document.flatten().associate { node ->
            node.path to NodeSnapshot(
                label = node.label,
                properties = node.properties.associate { property ->
                    property.name to normalizeRaw(property.rawValue)
                }
            )
        }
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
}
