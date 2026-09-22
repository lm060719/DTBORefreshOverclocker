package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceTreeScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val state = mutableStateOf(MainUiState())

    private fun source(node: String) = "/ {\n    $node {\n        value = <1>;\n    };\n};"

    private fun show(transactions: List<DeviceTreeTransaction> = emptyList()) {
        val root = temporary.newFolder()
        val dts = File(root, "dts").apply { mkdirs() }
        val files = listOf("alpha", "beta").mapIndexed { index, name ->
            File(dts, "entry_$index.dts").apply { writeText(source(name)) }
        }
        val entries = (0..1).map { DtboEntryMetadata(it, 0, 0, "0", "0", null, emptyList(), 0) }
        val metadata = DtboMetadata("0xd7b7ab1e", 0, 32, 32, 32, 4096, 0, entries)
        state.value = MainUiState(
            workspace = DtboWorkspace(root, File(root, "input.img"), File(root, "metadata"), metadata,
                DtboBinaryImage(metadata, byteArrayOf(), emptyList()), files, files, emptyList()),
            transactions = transactions
        )
        compose.setContent {
            MaterialTheme {
                DeviceTreeScreen(state.value, PaddingValues(0.dp),
                    onSetProperty = { _, _, _, _ -> }, onAddProperty = { _, _, _, _ -> },
                    onDeleteProperty = { _, _, _ -> }, onAddNode = { _, _, _ -> },
                    onCloneNode = { _, _, _ -> }, onRenameNode = { _, _, _ -> },
                    onDeleteNode = { _, _ -> }, onUndoChange = {})
            }
        }
        awaitText("/alpha")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun entrySwitchAndSearchUseOnlyTheCurrentDocument() {
        show()
        compose.onNodeWithText("Entry 1").performClick()
        awaitText("/beta")
        compose.onNodeWithText("/alpha").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("beta")
        awaitText("Entry 1 · 2 个节点 · 当前显示 1 · 0 条引用")
        compose.onNodeWithText("/beta").assertExists()
    }

    @Test
    fun revisionReloadsSameFileWithoutChangingTransactionCount() {
        show()
        compose.runOnIdle {
            state.value.workspace!!.dtsFiles.first().writeText(source("updated"))
            state.value = state.value.copy(workspaceRevision = state.value.workspaceRevision + 1)
        }
        awaitText("/updated")
        compose.onNodeWithText("/alpha").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(workspaceOperationInProgress = true, busy = true) }
        compose.onNodeWithText("/updated").assertDoesNotExist()
    }

    @Test
    fun laterModuleTransactionDisablesGenericUndoAcrossEntries() {
        val generic = DeviceTreeTransaction.generic(SetPropertyChange(0, "/alpha", "value", "<0>", "<1>"))
        val module = DeviceTreeTransaction(
            kind = DeviceTreeTransactionKind.RESOLUTION, summary = "module edit",
            operations = listOf(SetPropertyChange(1, "/beta", "value", "<0>", "<1>")),
            risk = DeviceTreeTransactionRisk.EXPORT_ONLY, directFlashAllowed = false
        )
        show(listOf(generic, module))
        compose.onNodeWithText("撤销").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(transactions = listOf(generic)) }
        awaitText("可撤销最近一项修改")
        compose.onNodeWithText("撤销").assertIsEnabled()
    }
}
