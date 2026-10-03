package io.mo.dtbooverclocker.core

import android.content.ContextWrapper
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboMetadata
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.ui.components.TimingUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * OPlus DTBO 把同一份面板 dtsi 编进两个都覆盖 &mdss_mdp 的 fragment，叠加后是同一个节点。
 * 长的 __fixups__ 字符串列表会被 dtc 反编译成字节数组，夹具按真机输出保持该格式。
 */
class OverlayCopyTimingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun duplicateFragmentsCollapseIntoOneCandidate() {
        val candidates = DtsTimingPatcher.analyzeEntry(0, dts(source))
        val panel = candidates.filter { !it.nodePath.startsWith("/fragment@1/") }
        assertEquals(listOf(60, 144), panel.map { it.currentHz })
        panel.forEach { candidate ->
            assertTrue(candidate.nodePath.startsWith("/fragment@2/"))
            assertEquals(listOf(candidate.nodePath.replace("fragment@2", "fragment@0")), candidate.overlayCopies.map { it.nodePath })
        }
        // 覆盖到其他目标的同名面板不是副本。
        val other = candidates.filter { it.nodePath.startsWith("/fragment@1/") }
        assertEquals(2, other.size)
        assertTrue(other.all { it.overlayCopies.isEmpty() })
    }

    @Test fun mirrorsAcrossEntriesAreNoLongerAmbiguous() {
        val workspace = workspace()
        val selected = workspace.candidates.single { it.entryIndex == 0 && it.nodePath == "$EFFECTIVE/timing@144" }
        assertEquals(listOf(1), TimingUtils.findMirrorCandidates(workspace.candidates, selected).map { it.entryIndex })
    }

    @Test fun overwriteWritesEveryCopyInOneUndoableTransaction() = runBlocking {
        val workspace = workspace()
        val selected = workspace.candidates.single { it.entryIndex == 0 && it.nodePath == "$EFFECTIVE/timing@144" }
        val transactionId = UUID.randomUUID().toString()
        val result = engine().applyTimingChange(workspace, selected, 165, PatchStrategy.PIXEL_CLOCK_ONLY,
            PatchMode.OVERWRITE_EXISTING, transactionId = transactionId)

        val updated = result.updatedWorkspace.candidates.single { it.id == result.selectedCandidateId }
        assertEquals(165, updated.currentHz)
        assertEquals(listOf(165), updated.overlayCopies.map { it.currentHz })
        assertEquals(setOf("$SHADOWED/timing@144", "$EFFECTIVE/timing@144"),
            result.operations.map { it.nodePath }.toSet())

        val restored = engine().restoreUndoSnapshot(result.updatedWorkspace, transactionId)!!
        assertEquals(source, restored.dtsFiles[0].readText())
    }

    @Test fun appendAddsTheSameNodeToEveryCopy() = runBlocking {
        val workspace = workspace()
        val selected = workspace.candidates.single { it.entryIndex == 0 && it.nodePath == "$EFFECTIVE/timing@144" }
        val result = engine().applyTimingChange(workspace, selected, 165, PatchStrategy.PIXEL_CLOCK_ONLY, PatchMode.APPEND_NEW)

        val panel = panel(result.updatedWorkspace.candidates)
        assertEquals(listOf(60, 144, 165), panel.map { it.currentHz })
        val added = panel.single { it.id == result.selectedCandidateId }
        assertEquals(165, added.currentHz)
        assertEquals(listOf(added.nodePath.replace("fragment@2", "fragment@0")), added.overlayCopies.map { it.nodePath })
    }

    @Test fun deleteRemovesEveryCopy() = runBlocking {
        val workspace = workspace()
        val selected = workspace.candidates.single { it.entryIndex == 0 && it.nodePath == "$EFFECTIVE/timing@144" }
        val result = engine().applyTimingChange(workspace, selected, 144, PatchStrategy.PIXEL_CLOCK_ONLY, PatchMode.DELETE_EXISTING)

        assertEquals(listOf(60), panel(result.updatedWorkspace.candidates).map { it.currentHz })
        val text = result.updatedWorkspace.dtsFiles[0].readText()
        assertEquals(1, Regex("timing@144").findAll(text).count()) // 只剩其他目标 fragment@1 中的那一个
        assertEquals(144, result.updatedWorkspace.candidates.single { it.entryIndex == 0 && it.nodePath.startsWith("/fragment@1/") && it.currentHz == 144 }.currentHz)
    }

    private fun panel(candidates: List<TimingCandidate>) = candidates.filter {
        it.entryIndex == 0 && TimingUtils.parsePanelIdentifier(it.nodePath) == PANEL && it.nodePath.startsWith("/fragment@2/")
    }

    private fun dts(text: String, entry: Int = 0): File = File(temp.root, "dts/entry_$entry.dts").apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun workspace(): DtboWorkspace {
        val root = temp.root
        val files = (0..1).map { dts(source, it) }
        val metadata = DtboMetadata("d7b7ab1e", 0, 0, 0, 0, 0, 0, emptyList())
        return DtboWorkspace(root, File(root, "dtbo.img"), File(root, "metadata"), metadata,
            DtboBinaryImage(metadata, ByteArray(0), emptyList()),
            (0..1).map { File(root, "entry.$it") }, files,
            files.flatMapIndexed { entry, file -> DtsTimingPatcher.analyzeEntry(entry, file) })
    }

    // Staging uses DTS files directly, so neither Android Context nor native tools are invoked.
    private fun engine() = DtboPatchEngine(allocate(ContextWrapper::class.java), allocate(NativeToolExecutor::class.java))

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object {
        const val PANEL = "qcom,mdss_dsi_oplus_test_cmd"
        const val SHADOWED = "/fragment@0/__overlay__/$PANEL/qcom,mdss-dsi-display-timings"
        const val EFFECTIVE = "/fragment@2/__overlay__/$PANEL/qcom,mdss-dsi-display-timings"

        fun panelBlock(supply: String) = """
                    __overlay__ {
                        $PANEL {
                            qcom,panel-supply-entries = <$supply>;
                            qcom,mdss-dsi-display-timings {
                                timing@60 {
                                    qcom,mdss-dsi-panel-framerate = <60>;
                                    qcom,mdss-dsi-panel-clockrate = <60000000>;
                                };
                                timing@144 {
                                    qcom,mdss-dsi-panel-framerate = <144>;
                                    qcom,mdss-dsi-panel-clockrate = <144000000>;
                                };
                            };
                        };
                    };"""

        val source = """
            /dts-v1/;
            / {
                fragment@0 {
                    target = <0xffffffff>;
            ${panelBlock("0x01")}
                };
                fragment@1 {
                    target = <0xffffffff>;
            ${panelBlock("0x02")}
                };
                fragment@2 {
                    target = <0xffffffff>;
            ${panelBlock("0x03")}
                };
                __fixups__ {
                    mdss_mdp = [ 2f 66 72 61 67 6d 65 6e 74 40 30 3a 74 61 72 67 65 74 3a 30 00 2f 66 72 61 67 6d 65 6e 74 40 32 3a 74 61 72 67 65 74 3a 30 00 ];
                    sde_dsi1 = "/fragment@1:target:0";
                };
            };
        """.trimIndent() + "\n"
    }
}
