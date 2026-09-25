package io.mo.dtbooverclocker.core

import android.content.ContextWrapper
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeEditor
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboMetadata
import io.mo.dtbooverclocker.model.DtboWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DtboPatchEngineUndoTest
{
    @get:Rule
    val temp = TemporaryFolder()

    private val source = """
        /dts-v1/;

        / {
        	panel {
        		timing@0 {
        			qcom,mdss-dsi-panel-framerate = <60>;
        		};
        		timing@1 {
        			qcom,mdss-dsi-panel-framerate = <120>;
        		};
        		timing@2 {
        			qcom,mdss-dsi-panel-framerate = <90>;
        		};
        	};
        };
    """.trimIndent() + "\n"

    @Test
    fun undoingNodeDeletionRestoresOriginalSiblingOrderByteForByte() = runBlocking {
        val (engine, workspace, dts) = setUp()
        val change = DeviceTreeEditor.buildDeleteNodeChange(0, source, "/panel/timing@1")

        val staged = engine.applyDeviceTreeChange(workspace, change, TRANSACTION_ID)
        assertFalse(dts.readText().contains("timing@1"))

        engine.restoreUndoSnapshot(staged, TRANSACTION_ID)
        assertEquals(source, dts.readText())
        assertFalse(File(workspace.rootDir, "undo_snapshots/$TRANSACTION_ID").exists())
    }

    @Test
    fun undoThroughEarlierTransactionRestoresItsPreImageAtomically() = runBlocking {
        val (engine, workspace, dts) = setUp()
        val setFramerate = DeviceTreeEditor.buildSetChange(0, source, "/panel/timing@0", "qcom,mdss-dsi-panel-framerate", "<144>")
        engine.applyDeviceTreeChange(workspace, setFramerate, TRANSACTION_ID)
        val afterFirst = dts.readText()
        val deleteNode = DeviceTreeEditor.buildDeleteNodeChange(0, afterFirst, "/panel/timing@1")
        engine.applyDeviceTreeChange(workspace, deleteNode, SECOND_TRANSACTION_ID)

        engine.restoreUndoSnapshots(workspace, listOf(TRANSACTION_ID, SECOND_TRANSACTION_ID))

        assertEquals(source, dts.readText())
        assertFalse(File(workspace.rootDir, "undo_snapshots/$TRANSACTION_ID").exists())
        assertFalse(File(workspace.rootDir, "undo_snapshots/$SECOND_TRANSACTION_ID").exists())
    }

    @Test
    fun undoingTransactionsOneByOneWalksBackThroughEachPreImage() = runBlocking {
        val (engine, workspace, dts) = setUp()
        engine.applyDeviceTreeChange(
            workspace,
            DeviceTreeEditor.buildSetChange(0, source, "/panel/timing@0", "qcom,mdss-dsi-panel-framerate", "<144>"),
            TRANSACTION_ID
        )
        val afterFirst = dts.readText()
        engine.applyDeviceTreeChange(
            workspace,
            DeviceTreeEditor.buildDeleteNodeChange(0, afterFirst, "/panel/timing@1"),
            SECOND_TRANSACTION_ID
        )

        engine.restoreUndoSnapshot(workspace, SECOND_TRANSACTION_ID)
        assertEquals(afterFirst, dts.readText())
        engine.restoreUndoSnapshot(workspace, TRANSACTION_ID)
        assertEquals(source, dts.readText())
    }

    @Test
    fun undoRefusesWhenFileChangedAfterTransaction() = runBlocking<Unit> {
        val (engine, workspace, dts) = setUp()
        val change = DeviceTreeEditor.buildSetChange(0, source, "/panel/timing@0", "qcom,mdss-dsi-panel-framerate", "<144>")
        engine.applyDeviceTreeChange(workspace, change, TRANSACTION_ID)
        dts.writeText(dts.readText().replace("<90>", "<30>"))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.restoreUndoSnapshot(workspace, TRANSACTION_ID) }
        }
        assertEquals(true, dts.readText().contains("<30>"))
    }

    @Test
    fun undoWithoutSnapshotReturnsNullForInverseFallback() = runBlocking {
        val (engine, workspace, _) = setUp()
        val change = DeviceTreeEditor.buildSetChange(0, source, "/panel/timing@0", "qcom,mdss-dsi-panel-framerate", "<144>")
        engine.applyDeviceTreeChange(workspace, change)

        assertNull(engine.restoreUndoSnapshot(workspace, TRANSACTION_ID))
    }

    private fun setUp(): Triple<DtboPatchEngine, DtboWorkspace, File>
    {
        val root = temp.newFolder()
        val dts = File(root, "dts/entry_0.dts").apply {
            parentFile!!.mkdirs()
            writeText(source)
        }
        val entry = File(root, "entries/entry.0").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(0))
        }
        val metadata = DtboMetadata("d7b7ab1e", 0, 0, 0, 0, 0, 0, emptyList())
        val workspace = DtboWorkspace(
            rootDir = root,
            inputImage = File(root, "dtbo.img"),
            metadataFile = File(root, "metadata"),
            metadata = metadata,
            binaryImage = DtboBinaryImage(metadata, ByteArray(0), emptyList()),
            extractedEntries = listOf(entry),
            dtsFiles = listOf(dts),
            candidates = emptyList()
        )
        // Staging and undo never touch Context or the native executor.
        val engine = DtboPatchEngine(allocate(ContextWrapper::class.java), allocate(NativeToolExecutor::class.java))
        return Triple(engine, workspace, dts)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T
    {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object
    {
        const val TRANSACTION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val SECOND_TRANSACTION_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    }
}
