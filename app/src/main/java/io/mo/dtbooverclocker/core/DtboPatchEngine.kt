package io.mo.dtbooverclocker.core

import android.content.Context
import android.net.Uri
import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.DtboSourceImage
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchReport
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.StagedChange
import io.mo.dtbooverclocker.model.SourceMode
import io.mo.dtbooverclocker.model.TimingCandidate
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
        val inspection = AvbImageEnvelope.validate(
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
                inspection.dtboTotalSize, inspection.logicalImageSize, inspection.layout?.footer
            )
        )
    }

    suspend fun resetWorkspace(workspace: DtboWorkspace): DtboWorkspace = withContext(Dispatchers.IO) {
        val dtsOriginalDir = File(workspace.rootDir, "dts_original")
        val dtsDir = File(workspace.rootDir, "dts")
        val refreshedCandidates = mutableListOf<TimingCandidate>()
        val dtsFiles = mutableListOf<File>()

        workspace.extractedEntries.forEachIndexed { index, _ ->
            val backupDts = File(dtsOriginalDir, "entry_$index.dts")
            val targetDts = File(dtsDir, "entry_$index.dts")
            if (backupDts.isFile) {
                backupDts.copyTo(targetDts, overwrite = true)
                dtsFiles += targetDts
                refreshedCandidates += DtsTimingPatcher.analyzeEntry(index, targetDts)
            }
        }
        logSink("[INFO] 工作区 DTS 已重置为初始状态，共恢复 ${refreshedCandidates.size} 个原始候选档位")
        workspace.copy(candidates = refreshedCandidates, dtsFiles = dtsFiles)
    }

    suspend fun applyTimingChange(
        workspace: DtboWorkspace,
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        mode: PatchMode = PatchMode.OVERWRITE_EXISTING,
        customParams: CustomTimingParams? = null
    ): TimingApplyResult = withContext(Dispatchers.IO) {
        require(candidate.entryIndex in workspace.extractedEntries.indices) {
            "候选节点对应的 DTB 索引无效"
        }

        val patchResult = DtsTimingPatcher.patch(candidate, targetHz, strategy, mode, customParams)
        candidate.dtsFile.writeText(patchResult.text)

        val refreshedForEntry = DtsTimingPatcher.analyzeEntry(candidate.entryIndex, candidate.dtsFile)
        val allCandidates = workspace.candidates.toMutableList()
        allCandidates.removeAll { it.entryIndex == candidate.entryIndex }
        allCandidates.addAll(refreshedForEntry)

        val nodeName = TimingUtils.parseTimingNodeName(candidate.nodePath)
        val nextSelectedId = when (mode) {
            PatchMode.APPEND_NEW -> {
                refreshedForEntry.firstOrNull { it.currentHz == targetHz }?.id
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
            updatedWorkspace = workspace.copy(candidates = allCandidates),
            selectedCandidateId = nextSelectedId,
            stagedChange = staged,
            changes = patchResult.changes,
            warnings = patchResult.warnings
        )
    }

    suspend fun packageStaged(
        workspace: DtboWorkspace,
        stagedChanges: List<StagedChange>,
        modifiedEntryIndices: Set<Int>
    ): PatchReport = withContext(Dispatchers.IO) {
        require(stagedChanges.isNotEmpty()) { "暂存修改列表为空，无需打包" }
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
                if (sanitized != currentText) {
                    dtsFile.writeText(sanitized)
                }

                val targetDtb = File(rebuiltDir, "entry_$index.dtb")
                val compile = executor.runDtc(
                    args = listOf(
                        "-I", "dts",
                        "-O", "dtb",
                        "-o", targetDtb.absolutePath,
                        dtsFile.absolutePath
                    ),
                    workingDir = workspace.rootDir
                )
                require(compile.isSuccess && targetDtb.isFile) {
                    "DTS[$index] 重编译失败：${compile.stderr.ifBlank { compile.stdout }.takeLast(2000)}"
                }

                val originalDecoded = workspace.binaryImage.entries[index].decodedBytes
                val rebuiltDecoded = targetDtb.readBytes()
                val modifiedPaths = stagedChanges
                    .filter { it.entryIndex == index }
                    .map { it.nodePath }
                    .toSet()
                verifyDtbIntegrity(index, originalDecoded, rebuiltDecoded, modifiedPaths)

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

        val rebuiltImage = DtboImageCodec.parse(outputImage)
        AvbImageEnvelope.validate(
            requireNotNull(rebuiltImage.originalBytes), rebuiltImage.metadata.totalSize, logSink, "FINAL_VALIDATE"
        )
        rebuiltImage.entries.forEachIndexed { index, entry ->
            val expected = replacementEntries[index] ?: workspace.binaryImage.entries[index].decodedBytes
            require(entry.decodedBytes.contentEquals(expected)) { "DTB[$index] 打包后字节与预期不一致" }
        }
        logSink("[OK] 完整镜像尾部与 AVB 摘要校验通过，全部 DTB 回读字节与预期一致")

        verifyMetadataPreserved(workspace, outputImage)
        verifyAllPatchedTimings(outputImage, modifiedEntryIndices, workspace)

        val allChanges = stagedChanges.map { it.summary }
        val warnings = mutableListOf<String>()
        if (stagedChanges.any { it.strategy == PatchStrategy.FRAMERATE_ONLY }) {
            warnings += "包含仅 Framerate 策略的修改，存在时序不匹配风险，不建议直接刷写。"
        }

        val lastChange = stagedChanges.last()
        logSink("[OK] 集中打包镜像生成完成：${outputImage.absolutePath} (包含 ${stagedChanges.size} 项修改)")

        PatchReport(
            outputImage = outputImage,
            targetHz = lastChange.targetHz,
            originalHz = lastChange.originalHz,
            strategy = lastChange.strategy,
            mode = lastChange.mode,
            customParams = lastChange.customParams,
            stagedChanges = stagedChanges,
            changes = allChanges,
            warnings = warnings
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
        return packageStaged(applyResult.updatedWorkspace, listOf(applyResult.stagedChange), setOf(candidate.entryIndex))
    }

    private fun verifyMetadataPreserved(workspace: DtboWorkspace, outputImage: File) {
        val rebuilt = DtboImageCodec.parse(outputImage)
        require(DtboImageCodec.metadataEquivalent(workspace.metadata, rebuilt.metadata)) {
            "重建后的 DTBO header/entry 关键元数据与原镜像不一致，已阻止输出进入刷写流程"
        }

        require(rebuilt.entries.size == workspace.binaryImage.entries.size) {
            "重建后的 DTBO entry 数量变化"
        }
        logSink("[OK] DTBO v${rebuilt.metadata.version} 元数据一致性校验通过")
    }

    private suspend fun verifyAllPatchedTimings(
        outputImage: File,
        modifiedEntryIndices: Set<Int>,
        workspace: DtboWorkspace
    ) {
        val verifyDir = File(workspace.rootDir, "verify_patch").apply {
            deleteRecursively()
            mkdirs()
        }
        val rebuilt = DtboImageCodec.parse(outputImage)
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
        modifiedTimingNodePaths: Set<String>
    ) {
        val origProps = FdtReader.readAllProperties(originalDtbBytes)
        val rebuiltProps = FdtReader.readAllProperties(rebuiltDtbBytes)

        val corrupted = mutableListOf<String>()
        origProps.forEach { (path, origVal) ->
            val nodePath = path.substringBeforeLast('/')
            val isModifiedTimingNode = modifiedTimingNodePaths.any { modifiedPath ->
                nodePath == modifiedPath || nodePath.startsWith("$modifiedPath/")
            }
            if (!isModifiedTimingNode) {
                val rebuiltVal = rebuiltProps[path]
                if (rebuiltVal == null || !rebuiltVal.contentEquals(origVal)) {
                    corrupted += "$path (原长度=${origVal.size}, 重建长度=${rebuiltVal?.size ?: 0})"
                }
            }
        }

        require(corrupted.isEmpty()) {
            val sample = corrupted.take(5).joinToString("; ")
            "DTB[$entryIndex] 完整性校验失败：检测到 ${corrupted.size} 个非时序属性被异常修改（例如：$sample）。已阻断打包。"
        }
        logSink("[OK] DTB[$entryIndex] 属性完整性校验通过：非修改节点的属性 100% 保持一致")
    }
}
