package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
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

    private fun showNavigation(enabled: Boolean = true): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                pager = rememberPagerState { StudioTab.entries.size }
                StudioNavigation(pager, rememberSaveableStateHolder(), enabled, {}, {}) { tab, _ ->
                    var text by rememberSaveable { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().testTag("page-${tab.name}")) {
                        OutlinedTextField(text, { text = it }, Modifier.testTag("input-${tab.name}"))
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
}
