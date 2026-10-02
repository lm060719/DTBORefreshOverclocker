package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeTransaction
import io.mo.dtbooverclocker.core.devicetree.SetPropertyChange
import io.mo.dtbooverclocker.model.*
import io.mo.dtbooverclocker.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class OverviewTimingNavigationTest(private val style: UiStyle) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun styles() = UiStyle.entries.map { arrayOf(it) }
    }
    @get:Rule val compose = createComposeRule()
    private lateinit var pager: PagerState
    private val activePanel = "qcom,mdss_dsi_o1_38_cmd"
    private val otherPanel = "custom_panel"
    private fun candidate(id: String, panel: String, entry: Int) =
        TimingCandidate(id, entry, File("$entry.dts"), "/$panel/display-timings/timing@0", 0, 1, 60)
    private val first = candidate("first", activePanel, 0)
    private val active = candidate("active", activePanel, 1)
    private val other = candidate("other", otherPanel, 0)
    private val wanted = candidate("wanted", otherPanel, 1)
    private val report = PatchReport(File("patched.img"), 75, 60, PatchStrategy.PIXEL_CLOCK_ONLY,
        changes = emptyList(), warnings = emptyList())
    private val transaction = DeviceTreeTransaction.refreshRate(
        StagedChange(mode = PatchMode.OVERWRITE_EXISTING, entryIndex = 1, nodePath = wanted.nodePath,
            nodeName = "timing@0", originalHz = 60, targetHz = 75, strategy = PatchStrategy.PIXEL_CLOCK_ONLY, summary = "60 → 75 Hz"),
        listOf(SetPropertyChange(1, wanted.nodePath, "qcom,mdss-dsi-panel-framerate", "<60>", "<75>")), emptyList())
    private var current by mutableStateOf(MainUiState())
    private var mounted by mutableStateOf(true)

    private fun show(): StateRestorationTester {
        val metadata = DtboMetadata("0xd7b7ab1e", 0, 32, 32, 32, 4096, 0, emptyList())
        current = MainUiState(workspace = DtboWorkspace(File("test"), File("dtbo.img"), File("metadata"), metadata,
            DtboBinaryImage(metadata, byteArrayOf(), emptyList()), emptyList(), emptyList(), listOf(first, active, other, wanted)),
            selectedCandidateId = first.id, activePanelIdentifier = activePanel, activePanelDisplayName = "O1-38 (CMD)",
            activeDtboEntries = setOf(1), transactions = listOf(transaction), patchReport = report,
            floatingBottomBar = style == UiStyle.MIUIX, liquidGlass = true,
            capabilityReport = CapabilityReport(2, 4, 4, emptyList(), emptyList()))
        val action = {}
        val timing = TimingActions(
            onSelect = { id -> current = current.selectTimingCandidate(id) },
            onTarget = { value -> current = current.copy(targetHz = value) },
            onStrategy = {}, onPatchMode = {}, onSyncAllDtbEntries = {}, onCustomPixelClock = {}, onCustomVfp = {}, onCustomVbp = {},
            onCustomHfp = {}, onCustomHbp = {}, onApplySuggestedCustom = action, onStageChange = action,
            onStageCharging = { _, _ -> }, onReportPanelIssue = action, onStageDeletion = {})
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                AppTheme(uiStyle = style) {
                    pager = rememberPagerState { 4 }
                    val holder = rememberSaveableStateHolder()
                    if (mounted) StudioScreen(current, pager, holder,
                        NavigationActions(action, action, action, action, action),
                        WorkspaceActions(action, action, action, action, action, {}), timing,
                        DeviceTreeActions({ _, _, _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _ -> },
                            { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }),
                        OutputActions({}, action, action, action, action, action, {}, {}, action, {}, action))
                }
            }
        }
    }

    private fun tab(tab: StudioTab) = compose.onNode(hasText(tab.label) and hasClickAction())
    private fun assertEditor(candidate: TimingCandidate) {
        compose.waitForIdle()
        tab(StudioTab.MODULES).assertIsSelected()
        compose.onNodeWithTag("timing-editor").assertIsDisplayed()
        compose.onNodeWithTag("timing-candidate-${candidate.id}", useUnmergedTree = true).assertIsSelected()
        compose.runOnIdle {
            assertEquals(candidate.id, current.selectedCandidateId)
            assertEquals(listOf(transaction), current.transactions)
            assertSame(report, current.patchReport)
        }
    }

    @Test fun previewActivePanelAndDetailLinksOpenExactEditorAndSurviveRestoration() {
        val restoration = show()
        // A deep link must also open refresh rate editing after the user had chosen Charging.
        tab(StudioTab.MODULES).performClick()
        compose.onNode(hasText("Charging") and hasClickAction()).performClick()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("overview-preview-panel"))
        compose.onNodeWithTag("overview-preview-panel").performClick()
        assertEditor(wanted)
        restoration.emulateSavedInstanceStateRestore()
        assertEditor(wanted)
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        assertEditor(wanted)

        tab(StudioTab.OVERVIEW).performClick()
        // Place the summary above the floating toolbar before sending a physical tap.
        compose.onNodeWithTag("overview-list").performScrollToIndex(3)
        compose.onNodeWithTag("overview-active-panel").performClick()
        assertEditor(active)

        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("overview-list").performScrollToIndex(3)
        compose.onNodeWithTag("overview-summary-details").performClick()
        compose.onNodeWithTag("overview-panel-$otherPanel").performScrollTo().performClick()
        assertEditor(wanted)
    }

    @Test fun busyStateDisablesPanelShortcutAndRepeatedSelectionKeepsTheDraft() {
        show()
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("overview-preview-panel"))
        compose.runOnIdle { current = current.copy(busy = true) }
        compose.onNodeWithTag("overview-preview-panel").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, pager.settledPage); current = current.copy(busy = false) }
        compose.onNodeWithTag("overview-preview-panel").performClick()
        assertEditor(wanted)
        compose.runOnIdle { current = current.copy(targetHz = 153) }
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("overview-preview-panel"))
        compose.onNodeWithTag("overview-preview-panel").performClick()
        assertEditor(wanted)
        compose.runOnIdle { assertEquals(153, current.targetHz) }
    }
}
