package io.mo.dtbooverclocker.core

import android.content.ContextWrapper
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboMetadata
import io.mo.dtbooverclocker.model.DtboWorkspace
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import io.mo.dtbooverclocker.ui.components.TimingUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class DtboPatchEngineSelectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun deleteStaysOnSamePanelAndDtb() = checkSelection(PatchMode.DELETE_EXISTING, false)
    @Test fun syncedDeleteStaysOnSamePanelAndDtb() = checkSelection(PatchMode.DELETE_EXISTING, true)
    @Test fun appendSelectsNewTimingOnSamePanelAndDtb() = checkSelection(PatchMode.APPEND_NEW, false)
    @Test fun syncedAppendSelectsNewTimingOnOriginalDtb() = checkSelection(PatchMode.APPEND_NEW, true)
    @Test fun overwriteKeepsOriginalTimingOnSamePanelAndDtb() = checkSelection(PatchMode.OVERWRITE_EXISTING, false)
    @Test fun syncedOverwriteKeepsOriginalTimingOnOriginalDtb() = checkSelection(PatchMode.OVERWRITE_EXISTING, true)

    @Test fun batchDeletionResolvesShiftedIdsAndUndoRestoresBothDtbs() = runBlocking {
        val batchSource = source.replace("timing@1 {", """
            timing@1 {
                qcom,mdss-dsi-panel-framerate = <90>;
                qcom,mdss-dsi-panel-clockrate = <90000000>;
            };
            timing@2 {
        """.trimIndent())
        val workspace = createWorkspace(batchSource)
        val engine = engine()
        val anchor = workspace.candidates.single { it.entryIndex == 1 && it.nodePath == SOURCE_PATH }
        val ids = workspace.candidates.filter {
            it.entryIndex == 1 && TimingUtils.parsePanelIdentifier(it.nodePath) == PANEL && it.currentHz != 120
        }.map { it.id }.toSet()
        val plan = TimingDeletionPlanner.plan(workspace.candidates, anchor, ids, true)
        assertEquals(4, plan.targets.size)
        var latest = workspace
        val transactions = mutableListOf<String>()
        for (target in plan.targets) {
            val live = latest.candidates.single { it.entryIndex == target.entryIndex && it.nodePath == target.nodePath }
            if (live.currentHz == 90) assertNotEquals(target.id, live.id)
            val transactionId = UUID.randomUUID().toString()
            transactions += transactionId
            latest = engine.applyTimingChange(latest, live, 90, PatchStrategy.PIXEL_CLOCK_ONLY,
                PatchMode.DELETE_EXISTING, transactionId = transactionId).updatedWorkspace
        }
        val selectedId = TimingDeletionPlanner.remainingSelection(latest.candidates, anchor)
        val selected = latest.candidates.single { it.id == selectedId }
        assertEquals(1, selected.entryIndex)
        assertEquals("/$PANEL/display-timings/timing@2", selected.nodePath)
        for (entry in 0..1) {
            assertEquals(listOf(120), latest.candidates.filter {
                it.entryIndex == entry && TimingUtils.parsePanelIdentifier(it.nodePath) == PANEL
            }.map { it.currentHz })
        }
        val restored = engine.restoreUndoSnapshots(latest, transactions)!!
        assertEquals(workspace.candidates, restored.candidates.sortedWith(compareBy({ it.entryIndex }, { it.nodeStart })))
        restored.dtsFiles.forEach { assertEquals(batchSource, it.readText()) }
    }

    private fun createWorkspace(sourceText: String = source): DtboWorkspace {
        val root = temp.newFolder()
        val files = (0..1).map { entry ->
            File(root, "dts/entry_$entry.dts").apply {
                parentFile!!.mkdirs()
                writeText(sourceText)
            }
        }
        val metadata = DtboMetadata("d7b7ab1e", 0, 0, 0, 0, 0, 0, emptyList())
        return DtboWorkspace(root, File(root, "dtbo.img"), File(root, "metadata"), metadata,
            DtboBinaryImage(metadata, ByteArray(0), emptyList()),
            (0..1).map { File(root, "entry.$it") }, files,
            files.flatMapIndexed { entry, file -> DtsTimingPatcher.analyzeEntry(entry, file) })
    }

    // Staging uses DTS files directly, so neither Android Context nor native tools are invoked.
    private fun engine() = DtboPatchEngine(allocate(ContextWrapper::class.java), allocate(NativeToolExecutor::class.java))

    private fun checkSelection(mode: PatchMode, sync: Boolean) = runBlocking {
        val workspace = createWorkspace()
        val engine = engine()
        val selected = workspace.candidates.single { it.entryIndex == 1 && it.nodePath == SOURCE_PATH }
        val mirrors = if (sync) TimingUtils.findMirrorCandidates(workspace.candidates, selected) else emptyList()
        assertEquals(if (sync) 1 else 0, mirrors.size)

        val primary = engine.applyTimingChange(workspace, selected, 90, PatchStrategy.PIXEL_CLOCK_ONLY, mode)
        var latest = primary.updatedWorkspace
        mirrors.forEach { mirror ->
            val live = latest.candidates.single { it.id == mirror.id }
            latest = engine.applyTimingChange(latest, live, 90, PatchStrategy.PIXEL_CLOCK_ONLY, mode).updatedWorkspace
        }

        // Mirror writes must preserve the primary result's selection even when candidates are reordered.
        val finalSelection = latest.candidates.single { it.id == primary.selectedCandidateId }
        assertEquals(1, finalSelection.entryIndex)
        assertEquals(PANEL, TimingUtils.parsePanelIdentifier(finalSelection.nodePath))
        val expectedPath = when (mode) {
            PatchMode.DELETE_EXISTING -> "/$PANEL/display-timings/timing@1"
            PatchMode.APPEND_NEW -> "/$PANEL/display-timings/timing@2"
            PatchMode.OVERWRITE_EXISTING -> SOURCE_PATH
        }
        assertEquals(expectedPath, finalSelection.nodePath)
        assertEquals(if (mode == PatchMode.DELETE_EXISTING) 120 else 90, finalSelection.currentHz)

        for (entry in 0..1) {
            val timings = latest.candidates.filter {
                it.entryIndex == entry && TimingUtils.parsePanelIdentifier(it.nodePath) == PANEL
            }
            val changed = entry == 1 || sync
            when (mode) {
                PatchMode.DELETE_EXISTING -> assertEquals(if (changed) listOf(120) else listOf(60, 120), timings.map { it.currentHz })
                PatchMode.APPEND_NEW -> assertEquals(if (changed) listOf(60, 120, 90) else listOf(60, 120), timings.map { it.currentHz })
                PatchMode.OVERWRITE_EXISTING -> assertEquals(if (changed) listOf(90, 120) else listOf(60, 120), timings.map { it.currentHz })
            }
            assertEquals(90, latest.candidates.single {
                it.entryIndex == entry && TimingUtils.parsePanelIdentifier(it.nodePath) == "${PANEL}_dvt02"
            }.currentHz)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object {
        const val PANEL = "qcom,mdss_dsi_test_cmd"
        const val SOURCE_PATH = "/$PANEL/display-timings/timing@0"
        val source = """
            /dts-v1/;
            / {
                ${PANEL}_dvt02 {
                    display-timings {
                        timing@0 {
                            qcom,mdss-dsi-panel-framerate = <90>;
                            qcom,mdss-dsi-panel-clockrate = <90000000>;
                        };
                    };
                };
                $PANEL {
                    display-timings {
                        timing@0 {
                            qcom,mdss-dsi-panel-framerate = <60>;
                            qcom,mdss-dsi-panel-clockrate = <60000000>;
                        };
                        timing@1 {
                            qcom,mdss-dsi-panel-framerate = <120>;
                            qcom,mdss-dsi-panel-clockrate = <120000000>;
                        };
                    };
                };
            };
        """.trimIndent() + "\n"
    }
}
