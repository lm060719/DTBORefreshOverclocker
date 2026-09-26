package io.mo.dtbooverclocker.core

import android.content.Context
import android.net.Uri
import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.AvbProtectionState
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboSourceImage
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchReport
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.StagedChange
import io.mo.dtbooverclocker.model.SourceMode
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange
import io.mo.dtbooverclocker.core.devicetree.DtsFileTransaction
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditValidator
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransactionKind
import io.mo.dtbooverclocker.core.devicetree.allOperations
import io.mo.dtbooverclocker.core.devicetree.modifiedEntryIndices
import io.mo.dtbooverclocker.core.devicetree.allowedNodePaths
import io.mo.dtbooverclocker.core.devicetree.allowedPropertyPath
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class TimingApplyResult(
    val updatedWorkspace: DtboWorkspace,
    val selectedCandidateId: String?,
    val stagedChange: StagedChange,
    val operations: List<DeviceTreeChange>,
    val changes: List<String>,
    val warnings: List<String>
)

class DtboPatchEngine(
    private val context: Context,
    private val executor: NativeToolExecutor,
    private val logSink: (String) -> Unit = {}
) {
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val importDir = File(context.cacheDir, "imports").apply { mkdirs() }
        val output = File(importDir, "dtbo_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            output.outputStream().use { out -> input.copyTo(out) }
        } ?: error("无法打开所选 content:// URI")

        require(output.length() >= 32) { "导入文件过小，不像有效 DTBO 镜像" }
        logSink("[INFO] 已将 SAF 文件复制到私有缓存：${output.absolutePath}")
        output
    }

    suspend fun exportFile(source: File, targetUri: Uri) = withContext(Dispatchers.IO) {
        require(source.isFile) { "待导出文件不存在：${source.absolutePath}" }
        context.contentResolver.openOutputStream(targetUri, "w")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: error("无法打开目标 URI 进行写入")
        logSink("[OK] 已导出 ${source.name}")
    }

    suspend fun analyze(
        image: File,
        sourceMode: SourceMode = SourceMode.LOCAL_IMAGE,
        sourcePath: String = image.absolutePath
    ): DtboWorkspace = withContext(Dispatchers.IO) {
        executor.validateToolchain().getOrThrow()
        require(image.isFile && image.length() >= 32) { "DTBO 镜像不存在或过小" }
        val sourceSize = image.length()
        val sourceHash = HashUtils.sha256(image)
        logSink("[IMAGE][SOURCE_INPUT] mode=$sourceMode, source=$sourcePath, file=${image.absolutePath}, " +
            "input_size=$sourceSize, input_sha256=$sourceHash")

        val workRoot = File(
            context.cacheDir,
            "dtbo_work/${System.currentTimeMillis()}_${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val entriesDir = File(workRoot, "entries").apply { mkdirs() }
        val dtsDir = File(workRoot, "dts").apply { mkdirs() }
        val dtsOriginalDir = File(workRoot, "dts_original").apply { mkdirs() }
        val stagedImage = File(workRoot, "dtbo_original.img")
        image.copyTo(stagedImage, overwrite = true)

        logSink("[INFO] 使用纯 Kotlin DTBO codec 解析表头、entry table 与压缩条目")
        val binaryImage = DtboImageCodec.parse(stagedImage)
        val inspection = AvbImageEnvelope.validateForAnalysis(
            requireNotNull(binaryImage.originalBytes), binaryImage.metadata.totalSize, logSink, "STAGED_INPUT"
        )
        require(inspection.containerSize.toLong() == sourceSize && inspection.sha256 == sourceHash) {
            "工作区镜像与导入源不一致，已停止处理：source_sha256=$sourceHash, staged_sha256=${inspection.sha256}"
        }
        val metadataFile = File(workRoot, "metadata.txt")
        metadataFile.writeText(DtboImageCodec.describe(binaryImage))

        logSink(
            "[INFO] DTBO version=${binaryImage.metadata.version}, " +
                "entries=${binaryImage.entries.size}, pageSize=${binaryImage.metadata.pageSize}"
        )

        val entries = binaryImage.entries.mapIndexed { index, entry ->
            File(entriesDir, "entry.$index").apply {
                writeBytes(entry.decodedBytes)
            }
        }

        val dtsFiles = mutableListOf<File>()
        val candidates = mutableListOf<TimingCandidate>()

        entries.forEachIndexed { index, entry ->
            val compression = binaryImage.entries[index].metadata.compressionFormat
            if (compression != 0) {
                logSink("[INFO] DTB[$index] 已在 Kotlin 层解压，compression=$compression")
            }

            val dts = File(dtsDir, "entry_$index.dts")
            val result = executor.runDtc(
                args = listOf(
                    "-I", "dtb",
                    "-O", "dts",
                    "-o", dts.absolutePath,
                    entry.absolutePath
                ),
                workingDir = workRoot
            )

            if (result.isSuccess && dts.isFile) {
                val rawText = dts.readText()
                val sanitized = DtsSanitizer.sanitize(rawText)
                if (sanitized != rawText) {
                    dts.writeText(sanitized)
                    logSink("[INFO] DTB[$index] 已自动净化含 \\0 转义序列的属性，防止 DTC 八进制转义截断")
                }
                dtsFiles += dts
                val backupDts = File(dtsOriginalDir, "entry_$index.dts")
                dts.copyTo(backupDts, overwrite = true)
                val found = DtsTimingPatcher.analyzeEntry(index, dts)
                candidates += found
                logSink("[INFO] DTB[$index] 找到 ${found.size} 个刷新率候选节点")
            } else {
                logSink("[WARN] DTB[$index] 无法由 dtc 反编译，保留原条目但不参与修补")
            }
        }

        DtboWorkspace(
            rootDir = workRoot,
            inputImage = stagedImage,
            metadataFile = metadataFile,
            metadata = binaryImage.metadata,
            binaryImage = binaryImage,
            extractedEntries = entries,
            dtsFiles = dtsFiles,
            candidates = candidates,
            sourceImage = DtboSourceImage(
                sourceMode, sourcePath, inspection.sha256, inspection.containerSize,
                inspection.dtboTotalSize, inspection.logicalImageSize, inspection.layout?.footer,
                inspection.protectionState, inspection.algorithm
            )
        )
    }

    suspend fun resetWorkspace(workspace: DtboWorkspace): DtboWorkspace = withContext(Dispatchers.IO) {
        val dtsOriginalDir = File(workspace.rootDir, "dts_original")
        val dtsDir = File(workspace.rootDir, "dts")
        val texts = workspace.extractedEntries.indices.mapNotNull { index ->
            val backup = File(dtsOriginalDir, "entry_$index.dts")
            if (backup.isFile) index to backup.readText() else null
        }.toMap()
        val updated = commitWorkspaceTexts(workspace, texts)
        File(workspace.rootDir, UNDO_SNAPSHOT_DIR).deleteRecursively()
        logSink("[INFO] 工作区 DTS 已重置为初始状态，共恢复 ${updated.candidates.size} 个原始候选档位")
        updated.copy(dtsFiles = texts.keys.map { File(dtsDir, "entry_$it.dts") })
    }


    suspend fun applyTimingChange(
        workspace: DtboWorkspace,
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
        customParams: CustomTimingParams? = null,
        transactionId: String? = null
    ): TimingApplyResult = withContext(Dispatchers.IO) {
        require(candidate.entryIndex in workspace.extractedEntries.indices) {
            "候选节点对应的 DTB 索引无效"
        }

        val plan = TimingDeviceTreePlanner.plan(
            candidate = candidate,
            targetHz = targetHz,
            strategy = strategy,
            mode = mode,
            customParams = customParams
        )

        // 真正写入工作区的内容来自通用 DeviceTreeChange 回放结果，而不是旧文本修补器。
        val updatedWorkspace = commitWorkspaceTexts(
            workspace, mapOf(candidate.entryIndex to plan.replayedText), undoSnapshotId = transactionId
        )
        val refreshedForEntry = updatedWorkspace.candidates.filter { it.entryIndex == candidate.entryIndex }

        val nodeName = TimingUtils.parseTimingNodeName(candidate.nodePath)
        val nextSelectedId = when (mode) {
            PatchMode.APPEND_NEW -> {
                refreshedForEntry.firstOrNull { it.nodePath == plan.targetNodePath }?.id
                    ?: refreshedForEntry.firstOrNull { it.currentHz == targetHz }?.id
                    ?: refreshedForEntry.firstOrNull()?.id
            }
            PatchMode.OVERWRITE_EXISTING -> {
                refreshedForEntry.firstOrNull { it.nodePath == candidate.nodePath }?.id
                    ?: refreshedForEntry.firstOrNull { it.currentHz == targetHz }?.id
                    ?: refreshedForEntry.firstOrNull()?.id
            }
            PatchMode.DELETE_EXISTING -> {
                refreshedForEntry.firstOrNull()?.id
            }
        }

        val summary = when (mode) {
            PatchMode.OVERWRITE_EXISTING ->
                "编辑档位 $nodeName: ${candidate.currentHz} Hz → $targetHz Hz (${strategy.displayName})"
            PatchMode.APPEND_NEW ->
                "新增档位 $targetHz Hz (基于原 $nodeName ${candidate.currentHz} Hz 模板 · ${strategy.displayName})"
            PatchMode.DELETE_EXISTING ->
                "删除档位 $nodeName (${candidate.currentHz} Hz)"
        }

        val staged = StagedChange(
            mode = mode,
            entryIndex = candidate.entryIndex,
            nodePath = candidate.nodePath,
            nodeName = nodeName,
            originalHz = candidate.currentHz,
            targetHz = targetHz,
            strategy = strategy,
            customParams = customParams,
            summary = summary
        )

        logSink("[OK] 已暂存时序修改：$summary")
        TimingApplyResult(
            updatedWorkspace = updatedWorkspace,
            selectedCandidateId = nextSelectedId,
            stagedChange = staged,
            operations = plan.operations,
            changes = plan.changes,
            warnings = plan.warnings
        )
    }


    suspend fun applyChargingChange(
        workspace: DtboWorkspace,
        snapshot: io.mo.dtbooverclocker.model.ChargingNode,
        inputs: Map<String, String>
    ): Pair<DtboWorkspace, DeviceTreeTransaction> = withContext(Dispatchers.IO) {
        val entryIndex = snapshot.entryIndex
        require(entryIndex in workspace.extractedEntries.indices) { "Charging 对应的 DTB 索引无效" }
        val dtsFile = File(workspace.rootDir, "dts/entry_$entryIndex.dts")
        require(dtsFile.isFile) { "Entry $entryIndex 没有可编辑的 DTS 文件" }
        val originalText = dtsFile.readText()
        val plan = ChargingPlanner.plan(originalText, snapshot, inputs)
        val updatedWorkspace = commitWorkspaceTexts(
            workspace, mapOf(entryIndex to plan.replayedText), undoSnapshotId = plan.transaction.id
        )
        plan.transaction.moduleChange?.changes?.forEach { logSink("[CHARGING] $it") }
        updatedWorkspace to plan.transaction
    }

    suspend fun applyDeviceTreeChange(
        workspace: DtboWorkspace,
        change: DeviceTreeChange,
        transactionId: String? = null
    ): DtboWorkspace = applyDeviceTreeChanges(
        workspace, listOf(change), validateReferences = true, transactionId = transactionId
    )

    suspend fun applyDeviceTreeChanges(
        workspace: DtboWorkspace,
        changes: List<DeviceTreeChange>,
        validateReferences: Boolean = false,
        transactionId: String? = null
    ): DtboWorkspace = withContext(Dispatchers.IO) {
        // Replay the entire transaction in memory before touching any live file.
        val texts = linkedMapOf<Int, String>()
        changes.forEach { change ->
            require(change.entryIndex in workspace.extractedEntries.indices) {
                "设备树修改对应的 DTB 索引无效：${change.entryIndex}"
            }
            val text = texts.getOrPut(change.entryIndex) {
                File(workspace.rootDir, "dts/entry_${change.entryIndex}.dts").readText()
            }
            // One parse serves both the reference validation and the edit itself.
            val document = DeviceTreeParser.parse(change.entryIndex, text)
            if (validateReferences) {
                DeviceTreeEditValidator.validate(document, change)
            }
            texts[change.entryIndex] = DeviceTreeEditor.apply(text, change, document)
        }
        val updated = commitWorkspaceTexts(workspace, texts, undoSnapshotId = transactionId)
        changes.forEach { logSink("[OK] 已暂存设备树修改：${it.summary}") }
        updated
    }

    /**
     * 撤销单个事务：直接写回该事务提交前的 DTS 原文快照，保证与撤销前逐字节一致
     * （节点与属性顺序不会漂移）。没有快照时返回 null，由调用方回退到逆操作回放。
     */
    suspend fun restoreUndoSnapshot(
        workspace: DtboWorkspace,
        transactionId: String
    ): DtboWorkspace? = restoreUndoSnapshots(workspace, listOf(transactionId))

    /**
     * 原子撤销队列末尾的连续事务 [transactionIds]（按提交顺序，从旧到新）。
     *
     * 每个 entry 写回「最早触及它的事务」的提交前快照，并要求当前文件等于「最晚触及它的事务」的提交后哈希。
     * 任一事务缺少快照时返回 null。
     */
    suspend fun restoreUndoSnapshots(
        workspace: DtboWorkspace,
        transactionIds: List<String>
    ): DtboWorkspace? = withContext(Dispatchers.IO) {
        require(transactionIds.isNotEmpty()) { "没有需要撤销的事务" }
        val snapshotDirs = transactionIds.map { undoSnapshotDir(workspace, it) }
        if (snapshotDirs.any { !it.isDirectory }) return@withContext null

        val preImages = linkedMapOf<Int, File>()
        val expectedCurrent = mutableMapOf<Int, String?>()
        snapshotDirs.forEach { dir ->
            dir.listFiles().orEmpty().forEach { file ->
                val index = UNDO_SNAPSHOT_FILE.matchEntire(file.name)?.groupValues?.get(1)?.toInt() ?: return@forEach
                preImages.putIfAbsent(index, file)
                expectedCurrent[index] = File(dir, "entry_$index.sha256").takeIf { it.isFile }?.readText()?.trim()
            }
        }
        require(preImages.isNotEmpty()) { "撤销快照为空：${transactionIds.joinToString()}" }

        val texts = preImages.mapValues { (index, snapshot) ->
            val current = File(workspace.rootDir, "dts/entry_$index.dts")
            val expected = expectedCurrent[index]
            require(expected != null && current.isFile && HashUtils.sha256(current) == expected) {
                "Entry $index 在该事务之后被修改过，无法安全撤销；请重置工作区"
            }
            snapshot.readText()
        }

        val updated = commitWorkspaceTexts(workspace, texts)
        snapshotDirs.forEach { it.deleteRecursively() }
        logSink("[OK] 已按快照撤销 ${transactionIds.size} 个事务，恢复 ${texts.size} 个 DTS 条目")
        updated
    }

    private fun commitWorkspaceTexts(
        workspace: DtboWorkspace,
        texts: Map<Int, String>,
        undoSnapshotId: String? = null
    ): DtboWorkspace {
        val files = texts.mapKeys { (index, _) -> File(workspace.rootDir, "dts/entry_$index.dts") }
        val snapshotDir = undoSnapshotId?.let { id ->
            undoSnapshotDir(workspace, id).apply {
                deleteRecursively()
                mkdirs()
                files.forEach { (file, _) -> file.copyTo(File(this, file.name), overwrite = true) }
            }
        }
        try {
            return DtsFileTransaction.commit(files) {
                // Post-image hashes let undo detect any write that bypassed the transaction queue.
                snapshotDir?.let { dir ->
                    texts.keys.forEach { index ->
                        File(dir, "entry_$index.sha256")
                            .writeText(HashUtils.sha256(File(workspace.rootDir, "dts/entry_$index.dts")))
                    }
                }
                val refreshed = texts.keys.flatMap { index ->
                    DtsTimingPatcher.analyzeEntry(index, File(workspace.rootDir, "dts/entry_$index.dts"))
                }
                workspace.copy(candidates = workspace.candidates.filterNot { it.entryIndex in texts } + refreshed)
            }
        } catch (failure: Throwable) {
            snapshotDir?.deleteRecursively()
            throw failure
        }
    }

    private fun undoSnapshotDir(workspace: DtboWorkspace, transactionId: String): File {
        require(UNDO_SNAPSHOT_ID.matches(transactionId)) { "无效的事务 ID：$transactionId" }
        return File(workspace.rootDir, "$UNDO_SNAPSHOT_DIR/$transactionId")
    }

    suspend fun packageStaged(
        workspace: DtboWorkspace,
        transactions: List<DeviceTreeTransaction>
    ): PatchReport = withContext(Dispatchers.IO) {
        require(transactions.isNotEmpty()) { "暂存事务列表为空，无需打包" }

        // Anyone able to flash this tool's output has an unlocked bootloader, which tolerates the
        // stale vendor signature; the hashes inside vbmeta are still rebuilt consistently.
        val signedSource = workspace.sourceImage?.avbProtectionState == AvbProtectionState.SIGNED

        val stagedChanges = transactions.mapNotNull { it.timingChange }
        val moduleStagedChanges = transactions.mapNotNull { it.moduleChange }
        val allOperations = transactions.allOperations()
        val genericChanges = transactions
            .filter { it.kind == DeviceTreeTransactionKind.GENERIC_EDIT }
            .flatMap { it.operations }
        val modifiedEntryIndices = transactions.modifiedEntryIndices()
        require(modifiedEntryIndices.isNotEmpty()) { "未检测到修改过的 DTB 条目" }

        val rebuiltDir = File(workspace.rootDir, "rebuilt_entries").apply {
            deleteRecursively()
            mkdirs()
        }

        val replacementEntries = mutableMapOf<Int, ByteArray>()
        val dtsDir = File(workspace.rootDir, "dts")

        for (index in workspace.extractedEntries.indices) {
            if (index in modifiedEntryIndices) {
                val dtsFile = File(dtsDir, "entry_$index.dts")
                require(dtsFile.isFile) { "条目 $index 对应的 DTS 文件不存在" }
                val currentText = dtsFile.readText()
                val sanitized = DtsSanitizer.sanitize(currentText)
                // Compile a staging copy so packaging cannot mutate the editor's source.
                val compileSource = File(rebuiltDir, "entry_$index.dts").apply { writeText(sanitized) }

                val targetDtb = File(rebuiltDir, "entry_$index.dtb")
                val compile = executor.runDtc(
                    args = listOf(
                        "-I", "dts",
                        "-O", "dtb",
                        "-o", targetDtb.absolutePath,
                        compileSource.absolutePath
                    ),
                    workingDir = workspace.rootDir
                )
                require(compile.isSuccess && targetDtb.isFile) {
                    "DTS[$index] 重编译失败：${compile.stderr.ifBlank { compile.stdout }.takeLast(2000)}"
                }

                val originalDecoded = workspace.binaryImage.entries[index].decodedBytes
                val rebuiltDecoded = targetDtb.readBytes()
                val declaredChanges = allOperations.filter { it.entryIndex == index }
                require(declaredChanges.isNotEmpty()) {
                    "Entry $index 被标记为已修改，但事务中没有对应的 DeviceTreeChange"
                }

                val modifiedProperties = declaredChanges
                    .mapNotNull { it.allowedPropertyPath() }
                    .toSet()
                val modifiedNodePaths = declaredChanges
                    .flatMap { it.allowedNodePaths() }
                    .toSet()
                verifyDtbIntegrity(
                    entryIndex = index,
                    originalDtbBytes = originalDecoded,
                    rebuiltDtbBytes = rebuiltDecoded,
                    modifiedTimingNodePaths = emptySet(),
                    modifiedPropertyPaths = modifiedProperties,
                    modifiedNodePaths = modifiedNodePaths
                )

                replacementEntries[index] = rebuiltDecoded
            }
        }

        val outputImage = File(workspace.rootDir, "dtbo_patched.img")
        if (outputImage.exists()) outputImage.delete()

        workspace.sourceImage?.let { source ->
            val actualHash = HashUtils.sha256(requireNotNull(workspace.binaryImage.originalBytes))
            logSink("[IMAGE][REBUILD_ORIGINAL] source=${source.sourcePath}, expected_sha256=${source.sha256}, " +
                "input_sha256=$actualHash")
            require(actualHash == source.sha256) { "重建输入与分析时的原始镜像不一致，已停止打包" }
        }

        logSink(
            "[INFO] 使用纯 Kotlin DTBO builder 重建镜像，共替换 ${replacementEntries.size} 个 DTB 条目"
        )
        DtboImageCodec.rebuild(
            original = workspace.binaryImage,
            replacementDecodedEntries = replacementEntries,
            output = outputImage,
            logSink = logSink
        )

        // Parsed once and shared by every check: partition-sized images (24 MB+) are expensive to hold twice.
        val rebuiltImage = DtboImageCodec.parse(outputImage)
        AvbImageEnvelope.validate(
            requireNotNull(rebuiltImage.originalBytes), rebuiltImage.metadata.totalSize, logSink, "FINAL_VALIDATE",
            allowSigned = signedSource
        )
        rebuiltImage.entries.forEachIndexed { index, entry ->
            val expected = replacementEntries[index] ?: workspace.binaryImage.entries[index].decodedBytes
            require(entry.decodedBytes.contentEquals(expected)) { "DTB[$index] 打包后字节与预期不一致" }
        }
        logSink("[OK] 完整镜像尾部与 AVB 摘要校验通过，全部 DTB 回读字节与预期一致")

        verifyMetadataPreserved(workspace, rebuiltImage)
        val timingVerificationEntries = transactions
            .filter { it.kind == DeviceTreeTransactionKind.REFRESH_RATE }
            .flatMap { it.entryIndices }
            .toSet()
        verifyAllPatchedTimings(
            rebuiltImage,
            timingVerificationEntries,
            workspace
        )

        val allChanges = transactions.flatMap { transaction ->
            transaction.moduleChange
                ?.changes
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(transaction.summary)
        }
        val warnings = transactions.flatMap { it.warnings }.distinct().toMutableList()
        if (stagedChanges.any { it.strategy == PatchStrategy.FRAMERATE_ONLY }) {
            warnings += "包含仅 Framerate 策略的修改，存在时序不匹配风险。"
        }
        if (moduleStagedChanges.isNotEmpty()) {
            warnings += "包含功能模块设备树修改（如 Charging），刷写前请确认参数取值。"
        }
        if (genericChanges.isNotEmpty()) {
            warnings += "包含通用设备树自由编辑，刷写前请确认修改内容。"
        }

        val lastChange = stagedChanges.lastOrNull()
        logSink(
            "[OK] 集中打包镜像生成完成：${outputImage.absolutePath} " +
                "(包含 ${transactions.size} 个事务 / ${allOperations.size} 个底层操作)"
        )

        PatchReport(
            outputImage = outputImage,
            targetHz = lastChange?.targetHz ?: 0,
            originalHz = lastChange?.originalHz ?: 0,
            strategy = lastChange?.strategy ?: PatchStrategy.CUSTOM,
            mode = lastChange?.mode ?: PatchMode.OVERWRITE_EXISTING,
            customParams = lastChange?.customParams,
            stagedChanges = stagedChanges,
            changes = allChanges,
            warnings = warnings.distinct()
        )
    }

    suspend fun patch(
        workspace: DtboWorkspace,
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
        customParams: CustomTimingParams? = null
    ): PatchReport {
        val applyResult = applyTimingChange(workspace, candidate, targetHz, strategy, mode, customParams)
        val transaction = DeviceTreeTransaction.refreshRate(
            stagedChange = applyResult.stagedChange,
            operations = applyResult.operations,
            warnings = applyResult.warnings
        )
        return packageStaged(
            workspace = applyResult.updatedWorkspace,
            transactions = listOf(transaction)
        )
    }

    private fun verifyMetadataPreserved(workspace: DtboWorkspace, rebuilt: DtboBinaryImage) {
        require(DtboImageCodec.metadataEquivalent(workspace.metadata, rebuilt.metadata)) {
            "重建后的 DTBO header/entry 关键元数据与原镜像不一致，已阻止输出进入刷写流程"
        }

        require(rebuilt.entries.size == workspace.binaryImage.entries.size) {
            "重建后的 DTBO entry 数量变化"
        }
        logSink("[OK] DTBO v${rebuilt.metadata.version} 元数据一致性校验通过")
    }

    private suspend fun verifyAllPatchedTimings(
        rebuilt: DtboBinaryImage,
        modifiedEntryIndices: Set<Int>,
        workspace: DtboWorkspace
    ) {
        val verifyDir = File(workspace.rootDir, "verify_patch").apply {
            deleteRecursively()
            mkdirs()
        }
        for (index in modifiedEntryIndices) {
            val targetEntry = rebuilt.entries.getOrNull(index)
                ?: error("修补后目标 DTB[$index] 条目缺失")
            val dtb = File(verifyDir, "verify_entry_$index.dtb")
            dtb.writeBytes(targetEntry.decodedBytes)
            val dts = File(verifyDir, "verify_entry_$index.dts")
            val decompile = executor.runDtc(
                args = listOf("-I", "dtb", "-O", "dts", "-o", dts.absolutePath, dtb.absolutePath),
                workingDir = verifyDir
            )
            require(decompile.isSuccess) { "修补后目标 DTB[$index] 无法反编译校验" }

            val verifiedCandidates = DtsTimingPatcher.analyzeEntry(index, dts)
            val expectedCandidates = workspace.candidates.filter { it.entryIndex == index }
            fun signature(c: TimingCandidate) = listOf(c.nodePath, c.currentHz, c.pixelClockHz,
                c.hActive, c.vActive, c.hFrontPorch, c.hBackPorch, c.hSync,
                c.vFrontPorch, c.vBackPorch, c.vSync, c.mdpTransferTimeUs, c.hasVendorDynamicMode)
            require(expectedCandidates.isNotEmpty() &&
                verifiedCandidates.map(::signature).toSet() == expectedCandidates.map(::signature).toSet()) {
                "DTB[$index] 校验失败：档位路径或时序参数与暂存修改不一致"
            }
            logSink("[OK] DTB[$index] 二次反编译校验通过：包含 ${verifiedCandidates.size} 个档位 (${verifiedCandidates.joinToString { "${it.currentHz}Hz" }})")
        }
    }

    private fun verifyDtbIntegrity(
        entryIndex: Int,
        originalDtbBytes: ByteArray,
        rebuiltDtbBytes: ByteArray,
        modifiedTimingNodePaths: Set<String>,
        modifiedPropertyPaths: Set<String>,
        modifiedNodePaths: Set<String>
    ) {
        val origProps = FdtReader.readAllProperties(originalDtbBytes)
        val rebuiltProps = FdtReader.readAllProperties(rebuiltDtbBytes)

        val corrupted = mutableListOf<String>()
        val allPaths = (origProps.keys + rebuiltProps.keys).toSortedSet()
        allPaths.forEach { path ->
            val origVal = origProps[path]
            val rebuiltVal = rebuiltProps[path]
            val unchanged = when {
                origVal == null && rebuiltVal == null -> true
                origVal == null || rebuiltVal == null -> false
                else -> rebuiltVal.contentEquals(origVal)
            }
            if (unchanged) {
                return@forEach
            }

            val nodePath = path.substringBeforeLast('/')
            val isModifiedTimingNode = modifiedTimingNodePaths.any { modifiedPath ->
                nodePath == modifiedPath || nodePath.startsWith("$modifiedPath/")
            }
            val isDeclaredProperty = path in modifiedPropertyPaths
            val isModifiedNode = modifiedNodePaths.any { modifiedPath ->
                nodePath == modifiedPath || nodePath.startsWith("$modifiedPath/")
            }
            if (!isModifiedTimingNode && !isDeclaredProperty && !isModifiedNode) {
                corrupted += "$path (原长度=${origVal?.size ?: 0}, 重建长度=${rebuiltVal?.size ?: 0})"
            }
        }

        require(corrupted.isEmpty()) {
            val sample = corrupted.take(5).joinToString("; ")
            "DTB[$entryIndex] 完整性校验失败：检测到 ${corrupted.size} 个未声明属性发生变化（例如：$sample）。已阻断打包。"
        }
        logSink("[OK] DTB[$entryIndex] 属性完整性校验通过：仅声明的属性或节点子树允许产生差异")
    }
}

private const val UNDO_SNAPSHOT_DIR = "undo_snapshots"
private val UNDO_SNAPSHOT_ID = Regex("^[A-Za-z0-9-]+$")
private val UNDO_SNAPSHOT_FILE = Regex("""^entry_(\d+)\.dts$""")
