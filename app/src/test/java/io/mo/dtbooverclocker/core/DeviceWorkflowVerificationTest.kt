package io.mo.dtbooverclocker.core

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeReferenceIndexer
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.model.AvbProtectionState
import io.mo.dtbooverclocker.model.CapabilityKind
import io.mo.dtbooverclocker.model.CapabilityReport
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.ui.components.TimingUtils
import io.mo.dtbooverclocker.util.HashUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt in with -PworkflowSampleDir=<cs directory> -PhostDtc=<host dtc executable>.
 * Drives the real DtboPatchEngine the same way MainViewModel does: import -> analyze ->
 * capability scan -> stage module / generic edits -> packageStaged -> export -> re-import.
 */
class DeviceWorkflowVerificationTest {
    @Test fun oneplusImageCompletesFullWorkflow() = verifyDevice("一加镜像")

    @Test fun realmeImageCompletesFullWorkflow() = verifyDevice("真我镜像")

    @Test fun meizuImageCompletesFullWorkflow() = verifyDevice("meizu21")

    @Test fun xiaomiImageCompletesFullWorkflow() = verifyDevice("小米15ultra镜像")

    private class Step(val name: String, val ok: Boolean, val detail: String, val guarded: Boolean = false)

    /** A planner refusing an unsafe edit is the intended outcome, not a workflow failure. */
    private class GuardRefusal(message: String) : Exception(message)

    private fun verifyDevice(folder: String, zeroTail: Boolean = false) = runBlocking {
        val sampleDir = System.getProperty("dtbo.workflowSampleDir", "") ?: ""
        val dtc = System.getProperty("dtbo.dtc", "") ?: ""
        assumeTrue("Workflow samples and host DTC were not configured", sampleDir.isNotBlank() && dtc.isNotBlank())
        val source = File(sampleDir, folder).listFiles { f -> f.extension == "img" }!!.single()
        val label = if (zeroTail) "$folder（尾部清零）" else folder
        val work = File(System.getProperty("java.io.tmpdir"), "dtbo_workflow/$label").apply {
            deleteRecursively(); mkdirs()
        }
        val image = if (!zeroTail) source else File(work, source.name).apply {
            val bytes = source.readBytes()
            val total = java.nio.ByteBuffer.wrap(bytes).getInt(4)
            bytes.fill(0, total, bytes.size)
            writeBytes(bytes)
        }
        val engine = DtboPatchEngine(fakeContext(work, File(dtc)), executorFor(work, File(dtc)))
        val steps = mutableListOf<Step>()
        suspend fun step(name: String, block: suspend () -> String) {
            val result = runCatching { block() }
            val failure = result.exceptionOrNull()
            steps += Step(name, result.isSuccess || failure is GuardRefusal,
                result.getOrElse { if (it is GuardRefusal) it.message!! else "${it::class.simpleName}: ${it.message}" },
                guarded = failure is GuardRefusal)
        }

        // 1. Import: MainViewModel.importImage copies the SAF stream into cache, then analyzes it.
        val imported = File(work, "imports/${image.name}").apply { parentFile!!.mkdirs() }
        image.copyTo(imported, overwrite = true)
        lateinit var workspace: DtboWorkspace
        step("导入 + 解析") {
            workspace = engine.analyze(imported)
            val entries = workspace.binaryImage.entries.size
            require(workspace.dtsFiles.size == entries) { "仅 ${workspace.dtsFiles.size}/$entries 个 DTB 被反编译" }
            require(workspace.candidates.isNotEmpty()) { "未识别到任何时序档位" }
            "entries=$entries, dts=${workspace.dtsFiles.size}, timing=${workspace.candidates.size}, " +
                "avb=${workspace.sourceImage?.avbProtectionState} ${workspace.sourceImage?.avbAlgorithm ?: ""}"
        }
        if (!steps.last().ok) return@runBlocking report(label, steps)
        val signed = workspace.sourceImage?.avbProtectionState == AvbProtectionState.SIGNED

        // 2. Automatic recognition: capability scan, panel matching and reference index.
        lateinit var capabilities: CapabilityReport
        step("能力扫描") {
            capabilities = CapabilityScanner.scan(workspace)
            capabilities.findings.joinToString("; ") { "${it.kind.displayName}=${it.status.displayName}(${it.matchCount})" } +
                " | ${capabilities.nodeCount} 节点 / ${capabilities.propertyCount} 属性"
        }
        step("时序档位识别") {
            workspace.candidates.groupBy { TimingUtils.parsePanelIdentifier(it.nodePath) }.entries.joinToString("\n      ") { (panel, list) ->
                "$panel: " + list.distinctBy { Triple(it.currentHz, it.hActive, it.vActive) }
                    .joinToString { "${it.currentHz}Hz ${it.hActive}x${it.vActive}" } + " (${list.size} 个, entry ${list.map { it.entryIndex }.distinct()})"
            }
        }
        step("在用面板自动匹配") {
            workspace.candidates.map { TimingUtils.parsePanelIdentifier(it.nodePath) }.distinct().joinToString { panel ->
                val match = ActivePanelDetector.findBestMatchCandidate(workspace.candidates, panel)
                require(match != null && TimingUtils.parsePanelIdentifier(match.nodePath) == panel) { "$panel 未匹配" }
                "$panel→${match.currentHz}Hz"
            }
        }
        step("按 dtbo_idx 推荐本机生效 DTB") {
            // Use, for every entry, a panel that entry contains (Meizu has no panel present in all 40 entries).
            workspace.binaryImage.entries.indices.filter { e -> workspace.candidates.any { it.entryIndex == e } }.joinToString { entry ->
                val panel = TimingUtils.parsePanelIdentifier(workspace.candidates.first { it.entryIndex == entry }.nodePath)
                val match = requireNotNull(ActivePanelDetector.findBestMatchCandidate(workspace.candidates, panel, setOf(entry)))
                require(match.entryIndex == entry) { "dtbo_idx=$entry 却推荐了 DTB[${match.entryIndex}]" }
                "idx=$entry→DTB[${match.entryIndex}]"
            }
        }
        step("引用索引") {
            workspace.dtsFiles.mapIndexed { index, file ->
                val refs = DeviceTreeReferenceIndexer.build(DeviceTreeParser.parse(index, file.readText()))
                "DTB$index: labels=${refs.labels.size} refs=${refs.references.size} unresolved=${refs.references.count { !it.resolved }}"
            }.joinToString("; ")
        }

        // Target the phone's own OPlus panel (e.g. mdss_dsi_panel_AD296_...), not Qualcomm reference panels.
        val vendorPanel = workspace.candidates.map { TimingUtils.parsePanelIdentifier(it.nodePath) }
            .firstOrNull { Regex("""mdss_dsi_panel_[A-Z]{2}\d{3}_|mdss_dsi_meizu_amoled_m\d{4}""").containsMatchIn(it) } ?: TimingUtils.parsePanelIdentifier(workspace.candidates.first().nodePath)
        val panelMatch = requireNotNull(ActivePanelDetector.findBestMatchCandidate(workspace.candidates, vendorPanel))
        val selected = panelMatch
        println("[$label] 目标面板 $vendorPanel → ${panelMatch.nodePath} (${panelMatch.currentHz}Hz, DTB${panelMatch.entryIndex})")

        // 3. Each module is staged on a clean workspace and packaged on its own, then everything together.
        val outDir = File(work, "exports").apply { mkdirs() }
        suspend fun packageAndExport(label: String, transactions: List<DeviceTreeTransaction>, current: DtboWorkspace,
                                     verify: (DtboWorkspace) -> String): String {
            val patch = engine.packageStaged(current, transactions)
            // MainViewModel.exportFile streams the report image to a SAF URI; the byte copy is the same.
            val exported = File(outDir, "${label}.img")
            patch.outputImage.copyTo(exported, overwrite = true)
            require(HashUtils.sha256(exported) == HashUtils.sha256(patch.outputImage)) { "导出副本与打包结果不一致" }
            require(exported.length() == image.length()) { "导出尺寸 ${exported.length()} ≠ 原始 ${image.length()}" }
            val reimported = engine.analyze(exported)
            if (signed) require(reimported.sourceImage?.avbProtectionState == AvbProtectionState.SIGNED) { "导出镜像的 AVB 结构丢失" }
            require(reimported.dtsFiles.size == reimported.binaryImage.entries.size) { "导出镜像重新导入后反编译不完整" }
            val untouched = workspace.binaryImage.entries.indices.filter { it !in transactions.flatMap { t -> t.entryIndices } }
            require(untouched.all { reimported.binaryImage.entries[it].decodedBytes.contentEquals(workspace.binaryImage.entries[it].decodedBytes) }) {
                "未修改的 DTB 条目字节发生变化"
            }
            return "${verify(reimported)} | 导出 ${exported.name} (${exported.length()} B, 未改动条目 ${untouched.size} 个逐字节一致)"
        }

        val staged = mutableListOf<DeviceTreeTransaction>()

        step("刷新率：${panelMatch.currentHz}→${panelMatch.currentHz + 24}Hz 暂存 + 打包导出") {
            val base = engine.resetWorkspace(workspace)
            val target = panelMatch.currentHz + 24
            val candidate = base.candidates.first { it.nodePath == panelMatch.nodePath && it.entryIndex == panelMatch.entryIndex }
            var appendRefusal = ""
            val result = runCatching {
                engine.applyTimingChange(base, candidate, target, PatchStrategy.BALANCED_BLANKING_TIME, PatchMode.APPEND_NEW)
            }.getOrElse { failure ->
                if (failure !is IllegalArgumentException) throw failure
                appendRefusal = "APPEND_NEW 被拒绝（${failure.message}），改用 OVERWRITE_EXISTING；"
                engine.resetWorkspace(workspace)
                engine.applyTimingChange(base, candidate, target, PatchStrategy.BALANCED_BLANKING_TIME, PatchMode.OVERWRITE_EXISTING)
            }
            val tx = DeviceTreeTransaction.refreshRate(result.stagedChange, result.operations, result.warnings, true)
            staged += tx
            packageAndExport("refresh_$target", listOf(tx), result.updatedWorkspace) { re ->
                val found = re.candidates.filter { it.entryIndex == candidate.entryIndex && it.currentHz == target }
                require(found.isNotEmpty()) { "重新导入后未找到 ${target}Hz 档位" }
                "$appendRefusal${result.stagedChange.summary}; 重新导入识别到 ${found.size} 个 ${target}Hz 档位"
            }
        }

        step("无 clockrate 档位：平衡时序 → 144Hz + 打包导出") {
            val base = engine.resetWorkspace(workspace)
            val candidate = base.candidates.firstOrNull {
                it.pixelClockHz == null && it.currentHz == 120 && it.hasFullGeometry && !it.hasVendorDynamicMode
            } ?: throw GuardRefusal("镜像中没有无 clockrate 的 120Hz 完整时序档位，跳过")
            val result = engine.applyTimingChange(base, candidate, 144, PatchStrategy.BALANCED_BLANKING_TIME, PatchMode.OVERWRITE_EXISTING)
            val tx = DeviceTreeTransaction.refreshRate(result.stagedChange, result.operations, result.warnings, true)
            packageAndExport("clockless_144", listOf(tx), result.updatedWorkspace) { re ->
                val after = re.candidates.first { it.entryIndex == candidate.entryIndex && it.nodePath == candidate.nodePath }
                require(after.currentHz == 144 && after.pixelClockHz == null) { "回读 ${after.currentHz}Hz clock=${after.pixelClockHz}" }
                val transfer = candidate.mdpTransferTimeUs
                if (transfer == null) {
                    require(after.vFrontPorch != candidate.vFrontPorch || after.vBackPorch != candidate.vBackPorch) { "前后肩未调整" }
                } else {
                    // Command mode with an MDP budget keeps porches and scales the transfer time instead.
                    require(after.vFrontPorch == candidate.vFrontPorch && after.mdpTransferTimeUs!! < transfer) {
                        "MDP 传输时间 $transfer→${after.mdpTransferTimeUs}，VFP ${candidate.vFrontPorch}→${after.vFrontPorch}"
                    }
                }
                "${TimingUtils.parsePanelIdentifier(candidate.nodePath)}: 120→144Hz，VFP ${candidate.vFrontPorch}→${after.vFrontPorch}，" +
                    "VBP ${candidate.vBackPorch}→${after.vBackPorch}" +
                    (transfer?.let { "，MDP 传输 $it→${after.mdpTransferTimeUs} µs" } ?: "") + "，未写入 clockrate，回读确认"
            }
        }

        step("Charging：修改一个参数 + 打包导出") {
            val base = engine.resetWorkspace(workspace)
            val nodes = CapabilityScanner.scan(base).chargingNodes
            require(nodes.isNotEmpty()) { "未识别到充电节点" }
            val (node, key, input) = nodes.asSequence().flatMap { node ->
                node.fields.asSequence().filter { it.issue == null && !it.parameter.boolean && it.value != null }.mapNotNull { field ->
                    val v = field.value!!
                    listOf(v - field.parameter.scale, v + field.parameter.scale).map { nv ->
                        ChargingAnalyzer.displayValue(field.copy(value = nv))
                    }.firstOrNull { runCatching { ChargingPlanner.preview(node, mapOf(field.inputKey to it)) }.isSuccess }
                        ?.let { Triple(node, field.inputKey, it) }
                }
            }.firstOrNull() ?: throw GuardRefusal("仅可分析：${nodes.size} 个充电节点中没有可编辑参数")
            val (updated, tx) = engine.applyChargingChange(base, node, mapOf(key to input))
            staged += tx
            packageAndExport("charging", listOf(tx), updated) { re ->
                val after = CapabilityScanner.scan(re).chargingNodes.first { it.entryIndex == node.entryIndex && it.nodePath == node.nodePath }
                val field = after.fields.first { it.inputKey == key }
                require(ChargingAnalyzer.displayValue(field) == input) { "重新导入后 $key=${ChargingAnalyzer.displayValue(field)}" }
                "DTB${node.entryIndex} ${node.nodePath.substringAfterLast('/')}: ${tx.moduleChange?.changes?.joinToString()} 已回读确认"
            }
        }

        step("设备树通用编辑（改/增/删属性、增/克隆/重命名/删节点、撤销）+ 打包导出") {
            var current = engine.resetWorkspace(workspace)
            val entry = selected.entryIndex
            fun text() = File(current.rootDir, "dts/entry_$entry.dts").readText()
            val panelPath = selected.nodePath.substringBefore("/qcom,mdss-dsi-display-timings")
            val doc = DeviceTreeParser.parse(entry, text())
            val panel = requireNotNull(doc.findNode(panelPath)) { "找不到面板节点 $panelPath" }
            val u32 = requireNotNull(panel.properties.firstOrNull { it.type.name == "U32" && !it.name.endsWith("phandle") }) { "面板节点没有 U32 属性" }
            fun cloneable(n: io.mo.dtbooverclocker.core.devicetree.DeviceTreeNode) = n.labels.isEmpty() && n.children.isEmpty() &&
                n.properties.none { it.name == "phandle" || it.name == "linux,phandle" }
            val cloneSource = doc.flatten().firstOrNull { cloneable(it) && it.path.startsWith("$panelPath/") }
                ?: doc.flatten().first { cloneable(it) && it.path.count { c -> c == '/' } >= 3 }
            val parentPath = cloneSource.path.substringBeforeLast('/')
            val ops = mutableListOf<DeviceTreeTransaction>()
            suspend fun apply(change: io.mo.dtbooverclocker.core.devicetree.DeviceTreeChange) {
                current = engine.applyDeviceTreeChange(current, change)
                ops += DeviceTreeTransaction.generic(change)
            }
            val newValue = "<0x${((u32.displayValue.toLong() + 1) and 0xffffffffL).toString(16)}>"
            apply(DeviceTreeEditor.buildSetChange(entry, text(), panelPath, u32.name, newValue))
            apply(DeviceTreeEditor.buildAddChange(entry, text(), panelPath, "dtbo-studio,verify", "<0x1>"))
            apply(DeviceTreeEditor.buildAddChange(entry, text(), panelPath, "dtbo-studio,to-delete", "\"x\""))
            apply(DeviceTreeEditor.buildDeleteChange(entry, text(), panelPath, "dtbo-studio,to-delete"))
            apply(DeviceTreeEditor.buildAddNodeChange(entry, text(), panelPath, "dtbo_studio_node"))
            apply(DeviceTreeEditor.buildCloneNodeChange(entry, text(), cloneSource.path, "${cloneSource.name.substringBefore('@')}_clone"))
            apply(DeviceTreeEditor.buildRenameNodeChange(entry, text(), "$parentPath/${cloneSource.name.substringBefore('@')}_clone", "dtbo_studio_renamed"))
            apply(DeviceTreeEditor.buildAddNodeChange(entry, text(), panelPath, "dtbo_studio_undo"))
            // Undo the last transaction exactly like MainViewModel.undoLastTransactionInternal.
            current = engine.applyDeviceTreeChanges(current, ops.removeAt(ops.lastIndex).operations.asReversed().map { it.inverse() })
            staged += ops
            packageAndExport("generic", ops.toList(), current) { re ->
                val redoc = DeviceTreeParser.parse(entry, File(re.rootDir, "dts/entry_$entry.dts").readText())
                val node = requireNotNull(redoc.findNode(panelPath))
                // dtc re-emits small cells zero-padded (<0x2> -> <0x02>), so compare values, not text.
                val written = io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec.decodeU32(
                    node.properties.first { it.name == u32.name }.rawValue)
                require(written == io.mo.dtbooverclocker.core.devicetree.DtsNumericValueCodec.decodeU32(newValue)) {
                    "${u32.name} 未回写：${node.properties.first { it.name == u32.name }.rawValue}"
                }
                require(node.properties.any { it.name == "dtbo-studio,verify" }) { "新增属性缺失" }
                require(node.properties.none { it.name == "dtbo-studio,to-delete" }) { "删除属性仍存在" }
                require(redoc.findNode("$panelPath/dtbo_studio_node") != null) { "新增节点缺失" }
                require(redoc.findNode("$parentPath/dtbo_studio_renamed") != null) { "克隆+重命名节点缺失" }
                require(redoc.findNode("$panelPath/dtbo_studio_undo") == null) { "撤销的节点仍存在" }
                "${ops.size} 个事务（${u32.name}→$newValue 等）重新导入逐项确认，撤销生效"
            }
        }

        step("能力扫描与打包并发（手机端 OOM 场景）") {
            // Reproduces staging followed immediately by 集中打包 while the background scan still runs.
            val base = engine.resetWorkspace(workspace)
            val candidate = base.candidates.first { it.nodePath == panelMatch.nodePath && it.entryIndex == panelMatch.entryIndex }
            val result = engine.applyTimingChange(base, candidate, candidate.currentHz + 24,
                PatchStrategy.BALANCED_BLANKING_TIME, PatchMode.APPEND_NEW)
            val tx = DeviceTreeTransaction.refreshRate(result.stagedChange, result.operations, result.warnings, true)
            val scan = kotlinx.coroutines.coroutineScope {
                val scanJob = async(kotlinx.coroutines.Dispatchers.Default) { CapabilityScanner.scan(result.updatedWorkspace) }
                engine.packageStaged(result.updatedWorkspace, listOf(tx))
                scanJob.await()
            }
            val runtime = Runtime.getRuntime()
            "扫描 ${scan.nodeCount} 节点与打包并发完成；堆上限 ${runtime.maxMemory() / 1048576} MB"
        }

        step("全部事务合并打包导出") {
            // Replay every staged operation on a fresh workspace, as the overview queue would.
            val base = engine.resetWorkspace(workspace)
            val refreshTx = staged.firstOrNull { it.timingChange != null }
            var cur = refreshTx?.timingChange?.let { t ->
                val cand = base.candidates.first { it.nodePath == t.nodePath && it.entryIndex == t.entryIndex }
                engine.applyTimingChange(base, cand, t.targetHz, t.strategy, t.mode).updatedWorkspace
            } ?: base
            val others = staged.filter { it.timingChange == null && it.moduleChange?.module?.name != "RESOLUTION" }
            cur = engine.applyDeviceTreeChanges(cur, others.flatMap { it.operations })
            val all = listOfNotNull(refreshTx) + others
            packageAndExport("combined", all, cur) { re ->
                "${all.size} 个事务 / ${all.sumOf { it.operationCount }} 个操作，重新导入 ${re.candidates.size} 个档位"
            }
        }

        report(label, steps)
    }


    private fun report(folder: String, steps: List<Step>) {
        println("\n===== $folder =====")
        steps.forEach { println("${if (it.guarded) "GUARD" else if (it.ok) "PASS" else "FAIL"}${it.name}\n      ${it.detail}") }
        val failed = steps.filterNot { it.ok }
        assertTrue("$folder: ${failed.size} 个步骤失败：${failed.joinToString { it.name }}", failed.isEmpty())
    }

    // Android Context/ApplicationInfo are stubs on the JVM; allocate without constructors and
    // answer only what DtboPatchEngine/NativeToolExecutor read.
    class HostContext : ContextWrapper(null) {
        override fun getCacheDir(): File = cache
        override fun getApplicationInfo(): ApplicationInfo = info
        companion object { lateinit var cache: File; lateinit var info: ApplicationInfo }
    }

    private fun fakeContext(work: File, dtc: File): HostContext {
        val native = File(work, "native").apply { mkdirs() }
        dtc.parentFile!!.listFiles()!!.filter { it.extension == "dll" }.forEach { it.copyTo(File(native, it.name), true) }
        dtc.copyTo(File(native, "libdtc.so"), overwrite = true).setExecutable(true)
        HostContext.cache = File(work, "cache").apply { mkdirs() }
        HostContext.info = allocate(ApplicationInfo::class.java).apply { nativeLibraryDir = native.absolutePath }
        return allocate(HostContext::class.java)
    }

    private fun executorFor(work: File, dtc: File) = NativeToolExecutor(fakeContext(work, dtc))

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
