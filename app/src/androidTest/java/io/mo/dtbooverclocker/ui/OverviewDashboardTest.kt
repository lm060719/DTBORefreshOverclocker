package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.*
import io.mo.dtbooverclocker.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OverviewDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val path = "/qcom,mdss_dsi_o1_38_cmd/display-timings/timing@0"
    private val metadata = DtboMetadata("0xd7b7ab1e", 0, 32, 32, 32, 4096, 0, emptyList())
    private val candidate = TimingCandidate("timing", 0, File("0.dts"), path, 0, 1, 60)
    private val workspace = DtboWorkspace(File("overview-test"), File("dtbo.img"), File("metadata"), metadata,
        DtboBinaryImage(metadata, byteArrayOf(), emptyList()), emptyList(), emptyList(), listOf(candidate))
    private fun state(mode: PatchMode = PatchMode.OVERWRITE_EXISTING): MainUiState {
        val change = StagedChange(mode = mode, entryIndex = 0, nodePath = path, nodeName = "timing@0",
            originalHz = 60, targetHz = 75, strategy = PatchStrategy.PIXEL_CLOCK_ONLY, summary = "60 → 75 Hz")
        val transaction = DeviceTreeTransaction.refreshRate(change,
            listOf(SetPropertyChange(0, path, "qcom,mdss-dsi-panel-framerate", "<60>", "<75>")), emptyList())
        return MainUiState(workspace = workspace, selectedCandidateId = candidate.id, targetHz = 144, transactions = listOf(transaction))
    }

    @Test fun previewUsesStagedTransactionInsteadOfEditorDraftAndHandlesDeletion() {
        var current by mutableStateOf(state())
        compose.setContent { AppTheme { RefreshOverviewCard(current) } }
        compose.onNodeWithText("75").assertExists()
        compose.onNodeWithText("144").assertDoesNotExist()
        compose.onNodeWithText("16.67 ms").assertExists()
        compose.onNodeWithText("13.33 ms").assertExists()
        val original = compose.onNodeWithTag("overview-original-interval").fetchSemanticsNode().size.width
        val target = compose.onNodeWithTag("overview-target-interval").fetchSemanticsNode().size.width
        assertEquals(0.8f, target.toFloat() / original, 0.01f)
        compose.runOnIdle { current = state(PatchMode.DELETE_EXISTING) }
        compose.onNodeWithText("删除").assertExists()
        compose.onNodeWithTag("overview-target-interval").assertDoesNotExist()
        compose.runOnIdle { current = current.copy(transactions = emptyList()) }
        compose.onNodeWithText("60").assertExists()
        compose.onNodeWithText("75").assertDoesNotExist()
    }

    @Test fun chartsRedrawWhenThemePaletteChangesWithoutLosingExpandedDetails() {
        var accent by mutableStateOf(Color(0xFF6650A4))
        compose.setContent {
            AppTheme(uiStyle = UiStyle.MIUIX, monet = false) {
                MaterialTheme(colorScheme = lightColorScheme(primary = accent)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RefreshOverviewCard(state())
                        ImageSummaryCard(state())
                    }
                }
            }
        }
        fun assertAccent(expected: Color) {
            compose.onNodeWithTag("overview-target-interval", useUnmergedTree = true).performScrollTo()
            val bar = compose.onNodeWithTag("overview-target-interval", useUnmergedTree = true).captureToImage().toPixelMap()
            assertEquals(expected, bar[bar.width / 2, bar.height / 2])
            compose.onNodeWithTag("overview-panel-chart", useUnmergedTree = true).performScrollTo()
            val donut = compose.onNodeWithTag("overview-panel-chart", useUnmergedTree = true).captureToImage().toPixelMap()
            // This fixture has one panel; the full donut uses its classification's primary color.
            assertEquals(expected, donut[donut.width / 2, 4])
        }
        assertAccent(accent)
        compose.onNodeWithText("查看详情").performScrollTo().performClick()
        compose.onNodeWithText("面板 DTB 实例: 1").assertExists()
        compose.runOnIdle { accent = Color(0xFF006C4C) }
        assertAccent(accent)
        compose.onNodeWithText("面板 DTB 实例: 1").assertExists()
    }

    @Test fun actualMonetAndDarkModePalettesReachThePreviewInBothStyles() {
        var style by mutableStateOf(UiStyle.MATERIAL)
        var monet by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var primary = Color.Unspecified
        compose.setContent {
            AppTheme(uiStyle = style, monet = monet, dark = dark) {
                primary = MaterialTheme.colorScheme.primary
                RefreshOverviewCard(state())
            }
        }
        for (selectedStyle in UiStyle.entries) for (enabled in listOf(false, true)) for (night in listOf(false, true)) {
            compose.runOnIdle { style = selectedStyle; monet = enabled; dark = night }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            val capture = compose.onNodeWithTag("overview-target-interval", useUnmergedTree = true)
                .assertIsDisplayed().captureToImage().toPixelMap()
            assertEquals(primary, capture[capture.width / 2, capture.height / 2])
            compose.onNodeWithText("75").assertExists()
        }
    }

    @Test fun pendingExportsAndBusyActionsStayDisabled() {
        var current by mutableStateOf(state().copy(busy = true))
        var clicks = 0
        val action = { clicks++; Unit }
        val output = OutputActions({ action() }, action, action, action, action, action, { action() }, { action() }, action, {}, action)
        compose.setContent {
            AppTheme(uiStyle = UiStyle.MIUIX) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SourceCard(current, action, action)
                    TransactionQueueCard(current, action, action, action)
                    OverviewOutputCard(current, output)
                }
            }
        }
        compose.onNodeWithTag("overview-import").assertIsNotEnabled()
        compose.onNodeWithTag("overview-package").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current = current.copy(busy = false) }
        compose.onNodeWithTag("overview-package").assertIsEnabled()
        compose.onNode(hasText("DTBO 镜像") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Root 模块") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun overviewWithFloatingGlassNavigationRendersAndKeepsTheLastRowReachable() {
        var monet by mutableStateOf(false)
        var dark by mutableStateOf(false)
        val entries = (0 until 8).map { DtboEntryMetadata(it, 0, 0, "0", "0", null, emptyList(), 0) }
        val loadedMetadata = metadata.copy(entries = entries)
        val loaded = state().copy(workspace = workspace.copy(metadata = loadedMetadata),
            rootState = RootState(true, true), slotInfo = SlotInfo("_a", "槽位 A", "/dev/block/dtbo_a", "_b"),
            activePanelDisplayName = "O1-38", status = "工作区已加载", logs = listOf("已识别 1 个时序候选"),
            capabilityReport = CapabilityReport(8, 1, 1, emptyList(), emptyList()))
        val action = {}
        val workspaceActions = WorkspaceActions(action, action, action, action, action, {})
        val output = OutputActions({}, action, action, action, action, action, {}, {}, action, {}, action)
        compose.setContent {
            AppTheme(uiStyle = UiStyle.MIUIX, monet = monet, dark = dark) {
                StudioNavigation(rememberPagerState { 4 }, rememberSaveableStateHolder(), true, action, action,
                    floatingBottomBar = true, liquidGlass = true) { tab, padding ->
                    if (tab == StudioTab.OVERVIEW) OverviewTab(loaded, padding, workspaceActions, output, action)
                }
            }
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        screenshot("overview-miuix-glass.png")
        compose.onNodeWithTag("overview-list").performScrollToNode(hasText("校验与备份"))
        screenshot("overview-miuix-glass-scrolled.png")
        compose.onNodeWithTag("overview-list").performScrollToIndex(0)
        compose.runOnIdle { monet = true }
        screenshot("overview-miuix-monet-glass.png")
        compose.runOnIdle { dark = true }
        screenshot("overview-miuix-dark-glass.png")
        compose.onNodeWithTag("overview-list").performScrollToIndex(9)
        compose.onAllNodesWithText("工作区已加载").onLast().assertIsDisplayed()
    }
}
