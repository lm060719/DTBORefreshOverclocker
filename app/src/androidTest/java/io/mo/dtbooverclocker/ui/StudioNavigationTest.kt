package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.components.UiStyleSelector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StudioNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var pager: PagerState
    private var style by mutableStateOf(UiStyle.MATERIAL)

    private fun showNavigation(enabled: Boolean = true, uiStyle: UiStyle = UiStyle.MATERIAL): StateRestorationTester {
        style = uiStyle
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppTheme(uiStyle = style) {
                pager = rememberPagerState { StudioTab.entries.size }
                StudioNavigation(pager, rememberSaveableStateHolder(), enabled, {}, {}) { tab, _ ->
                    var text by rememberSaveable { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().testTag("page-${tab.name}")) {
                        OutlinedTextField(text, { text = it }, Modifier.testTag("input-${tab.name}"))
                        if (tab == StudioTab.SETTINGS) UiStyleSelector(style) { style = it }
                    }
                }
            }
        }
        return restoration
    }

    private fun tab(tab: StudioTab) = compose.onNode(hasText(tab.label) and hasClickAction())

    @Test
    fun swipesAndTabClicksStayInSync() {
        showNavigation()
        compose.onNodeWithTag("page-OVERVIEW").performTouchInput { swipeLeft() }
        tab(StudioTab.MODULES).assertIsSelected()
        compose.onNodeWithTag("page-MODULES").performTouchInput { swipeRight() }
        tab(StudioTab.OVERVIEW).assertIsSelected()
        tab(StudioTab.SETTINGS).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(StudioTab.SETTINGS.ordinal, pager.settledPage) }
        compose.onNodeWithTag("page-SETTINGS").assertIsDisplayed()
    }

    @Test
    fun pageInputsSurviveSwitchingAndStateRestoration() {
        val restoration = showNavigation()
        compose.onNodeWithTag("input-OVERVIEW").performTextInput("saved query")
        tab(StudioTab.SETTINGS).performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        tab(StudioTab.SETTINGS).assertIsSelected()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("input-OVERVIEW").assertTextEquals("saved query")
    }

    @Test
    fun busyStateDisablesSwipesAndTabClicks() {
        showNavigation(enabled = false)
        tab(StudioTab.MODULES).assertIsNotEnabled()
        compose.onNodeWithTag("page-OVERVIEW").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, pager.settledPage) }
    }

    @Test
    fun miuixSwipesAndTabClicksStayInSync() {
        showNavigation(uiStyle = UiStyle.MIUIX)
        compose.onNodeWithTag("page-OVERVIEW").performTouchInput { swipeLeft() }
        tab(StudioTab.MODULES).assertIsSelected()
        tab(StudioTab.SETTINGS).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(StudioTab.SETTINGS.ordinal, pager.settledPage) }
    }

    @Test
    fun switchingStylesPreservesPageAndSavedInputs() {
        showNavigation()
        compose.onNodeWithTag("input-OVERVIEW").performTextInput("keep this query")
        tab(StudioTab.SETTINGS).performClick()
        compose.onNodeWithTag("ui-style-miuix").performClick()
        compose.onNodeWithTag("ui-style-selector").assertTextContains("Miuix")
        tab(StudioTab.SETTINGS).assertIsSelected()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("input-OVERVIEW").assertTextEquals("keep this query")
        tab(StudioTab.SETTINGS).performClick()
        compose.onNodeWithTag("ui-style-selector").performClick()
        compose.onNode(hasText("Material 3") and hasClickAction()).performClick()
        compose.onNodeWithTag("ui-style-material").assertIsSelected()
        tab(StudioTab.SETTINGS).assertIsSelected()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("input-OVERVIEW").assertTextEquals("keep this query")
    }

    @Test
    fun miuixBusyStateDisablesNavigation() {
        showNavigation(enabled = false, uiStyle = UiStyle.MIUIX)
        tab(StudioTab.MODULES).assertIsNotEnabled()
        compose.onNodeWithTag("page-OVERVIEW").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, pager.settledPage) }
    }
}
