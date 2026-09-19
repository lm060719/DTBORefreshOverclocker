package io.mo.dtbooverclocker.core

import android.content.Context
import android.net.Uri
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchReport
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    suspend fun analyze(image: File): DtboWorkspace = withContext(Dispatchers.IO) {
        executor.validateToolchain().getOrThrow()
        require(image.isFile && image.length() >= 32) { "DTBO 镜像不存在或过小" }

        val workRoot = File(
            context.cacheDir,
            "dtbo_work/${System.currentTimeMillis()}_${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val entriesDir = File(workRoot, "entries").apply { mkdirs() }
        val dtsDir = File(workRoot, "dts").apply { mkdirs() }
        val stagedImage = File(workRoot, "dtbo_original.img")
        image.copyTo(stagedImage, overwrite = true)

        logSink("[INFO] 使用纯 Kotlin DTBO codec 解析表头、entry table 与压缩条目")
        val binaryImage = DtboImageCodec.parse(stagedImage)
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
                dtsFiles += dts
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
            candidates = candidates
        )
    }

    suspend fun patch(
        workspace: DtboWorkspace,
        candidate: TimingCandidate,
        targetHz: Int,
        strategy: PatchStrategy,
        mode: PatchMode = PatchMode.OVERWRITE_EXISTING
    ): PatchReport = withContext(Dispatchers.IO) {
        require(candidate.entryIndex in workspace.extractedEntries.indices) {
            "候选节点对应的 DTB 索引无效"
        }

        val patchText = DtsTimingPatcher.patch(candidate, targetHz, strategy, mode)
        val patchedDts = File(workspace.rootDir, "patched_entry_${candidate.entryIndex}.dts")
        patchedDts.writeText(patchText.text)

        val rebuiltDir = File(workspace.rootDir, "rebuilt_entries").apply {
            deleteRecursively()
            mkdirs()
        }

        var patchedEntry: File? = null
        workspace.extractedEntries.forEachIndexed { index, originalEntry ->
            val target = File(rebuiltDir, "entry_$index.dtb")
            if (index == candidate.entryIndex) {
                val compile = executor.runDtc(
                    args = listOf(
                        "-I", "dts",
                        "-O", "dtb",
                        "-o", target.absolutePath,
                        patchedDts.absolutePath
                    ),
                    workingDir = workspace.rootDir
                )
                require(compile.isSuccess && target.isFile) {
                    "DTS 重编译失败：${compile.stderr.ifBlank { compile.stdout }.takeLast(2000)}"
                }
                patchedEntry = target
            } else {
                originalEntry.copyTo(target, overwrite = true)
            }
        }

        val replacement = patchedEntry ?: error("未生成目标 DTB")
        val outputImage = File(workspace.rootDir, "dtbo_patched_${targetHz}hz.img")
        if (outputImage.exists()) outputImage.delete()

        val originalCompression = workspace.binaryImage.entries[candidate.entryIndex].metadata.compressionFormat
        logSink(
            "[INFO] 使用纯 Kotlin DTBO builder 重建镜像；目标 entry 保持原 compression=$originalCompression"
        )
        DtboImageCodec.rebuild(
            original = workspace.binaryImage,
            replacementDecodedEntries = mapOf(candidate.entryIndex to replacement.readBytes()),
            output = outputImage
        )

        verifyMetadataPreserved(workspace, outputImage)
        verifyPatchedTiming(outputImage, candidate, targetHz, mode, workspace.rootDir)

        logSink("[OK] 修补镜像生成完成：${outputImage.absolutePath}")
        PatchReport(
            outputImage = outputImage,
            targetHz = targetHz,
            originalHz = candidate.currentHz,
            strategy = strategy,
            mode = mode,
            changes = patchText.changes,
            warnings = patchText.warnings
        )
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

    private suspend fun verifyPatchedTiming(
        outputImage: File,
        originalCandidate: TimingCandidate,
        targetHz: Int,
        mode: PatchMode,
        workRoot: File
    ) {
        val verifyDir = File(workRoot, "verify_patch").apply {
            deleteRecursively()
            mkdirs()
        }
        val rebuilt = DtboImageCodec.parse(outputImage)
        val targetEntry = rebuilt.entries.getOrNull(originalCandidate.entryIndex)
            ?: error("修补后目标 DTB 条目缺失")

        val dtb = File(verifyDir, "target_verify.dtb")
        dtb.writeBytes(targetEntry.decodedBytes)
        val dts = File(verifyDir, "target_verify.dts")
        val decompile = executor.runDtc(
            args = listOf("-I", "dtb", "-O", "dts", "-o", dts.absolutePath, dtb.absolutePath),
            workingDir = verifyDir
        )
        require(decompile.isSuccess) { "修补后目标 DTB 无法反编译校验" }

        val matches = DtsTimingPatcher.analyzeEntry(originalCandidate.entryIndex, dts)
        val hasTarget = matches.any { it.currentHz == targetHz }
        require(hasTarget) { "重建后未能在目标 DTB 中确认 $targetHz Hz 属性，拒绝进入刷写流程" }

        if (mode == PatchMode.APPEND_NEW) {
            val hasOriginal = matches.any { it.currentHz == originalCandidate.currentHz }
            require(hasOriginal) { "新增档位模式下，目标 DTB 未能保留原始 ${originalCandidate.currentHz} Hz 档位" }
            logSink("[OK] 目标刷新率与原始刷新率双重校验通过：新增 $targetHz Hz，保留 ${originalCandidate.currentHz} Hz")
        } else {
            logSink("[OK] 目标刷新率二次反编译校验通过：$targetHz Hz")
        }
    }
}
