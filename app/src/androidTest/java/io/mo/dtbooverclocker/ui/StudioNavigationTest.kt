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
import io.mo.dtbooverclocker.ui.components.BottomBarPreferences
import io.mo.dtbooverclocker.ui.components.MonetPreference
import io.mo.dtbooverclocker.ui.theme.supportsMonet
import io.mo.dtbooverclocker.ui.components.supportsLiquidGlass
import androidx.compose.ui.geometry.Offset
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
    private var floating by mutableStateOf(false)
    private var glass by mutableStateOf(false)
    private var monet by mutableStateOf(true)

    private fun showNavigation(enabled: Boolean = true, uiStyle: UiStyle = UiStyle.MATERIAL,
                               floatingBar: Boolean = false, glassEffect: Boolean = false): StateRestorationTester {
        style = uiStyle
        floating = floatingBar
        glass = glassEffect
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppTheme(uiStyle = style, monet = monet) {
                pager = rememberPagerState { StudioTab.entries.size }
                StudioNavigation(pager, rememberSaveableStateHolder(), enabled, {}, {},
                    floatingBottomBar = floating, liquidGlass = glass) { tab, _ ->
                    var text by rememberSaveable { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().testTag("page-${tab.name}")) {
                        OutlinedTextField(text, { text = it }, Modifier.testTag("input-${tab.name}"))
                        if (tab == StudioTab.SETTINGS) {
                            UiStyleSelector(style) { style = it }
                            MonetPreference(monet) { monet = it }
                            BottomBarPreferences(floating, glass, { floating = it }, { glass = it })
                        }
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

    @Test
    fun effectSwitchesPreserveDraftsAndGlassChoiceAcrossStyles() {
        org.junit.Assume.assumeTrue(supportsLiquidGlass())
        showNavigation()
        compose.onNodeWithTag("input-OVERVIEW").performTextInput("floating draft")
        tab(StudioTab.SETTINGS).performClick()
        compose.onNodeWithTag("liquid-glass").assertIsNotEnabled()
        compose.onNodeWithTag("floating-bottom-bar").performClick()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        compose.onNodeWithTag("liquid-glass").performClick().assertIsOn()
        compose.onNodeWithTag("glass-indicator").assertExists()
        compose.onNodeWithTag("ui-style-miuix").performClick()
        compose.onNodeWithTag("floating-bottom-bar").assertIsOn().performClick()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithTag("liquid-glass", useUnmergedTree = true).assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("floating-bottom-bar").performClick()
        compose.onNodeWithTag("glass-indicator").assertExists()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("input-OVERVIEW").assertTextEquals("floating draft")
    }

    @Test
    fun floatingBarDragSelectsTabsWithAndWithoutGlassInBothStyles() {
        showNavigation(floatingBar = true)
        for (uiStyle in UiStyle.entries) {
            for (glassEnabled in listOf(false, true)) {
                compose.runOnIdle { style = uiStyle; glass = glassEnabled }
                tab(StudioTab.OVERVIEW).performClick()
                compose.onNodeWithTag("floating-navigation").performTouchInput {
                    swipe(Offset(width * 0.125f, height / 2f), Offset(width * 0.875f, height / 2f), 500)
                }
                tab(StudioTab.SETTINGS).assertIsSelected()
                compose.runOnIdle { assertEquals(StudioTab.SETTINGS.ordinal, pager.settledPage) }
            }
        }
    }

    @Test
    fun busyFloatingGlassBarDisablesClicksAndDragging() {
        showNavigation(enabled = false, uiStyle = UiStyle.MIUIX, floatingBar = true, glassEffect = true)
        tab(StudioTab.SETTINGS).assertIsNotEnabled()
        tab(StudioTab.SETTINGS).performTouchInput { click() }
        compose.onNodeWithTag("floating-navigation").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, pager.settledPage) }
    }

    @Test
    fun monetSwitchPreservesDraftsAndNavigationAcrossStyles() {
        org.junit.Assume.assumeTrue(supportsMonet())
        showNavigation(floatingBar = true, glassEffect = true)
        compose.onNodeWithTag("input-OVERVIEW").performTextInput("keep color draft")
        tab(StudioTab.SETTINGS).performClick()
        compose.onNodeWithTag("monet-colors").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("ui-style-miuix").performClick()
        compose.onNodeWithTag("monet-colors").assertIsOff().performClick().assertIsOn()
        tab(StudioTab.SETTINGS).assertIsSelected()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("input-OVERVIEW").assertTextEquals("keep color draft")
        tab(StudioTab.SETTINGS).performClick()
        compose.onNodeWithTag("ui-style-selector").performClick()
        compose.onNode(hasText("Material 3") and hasClickAction()).performClick()
        compose.onNodeWithTag("monet-colors").assertIsOn()
        tab(StudioTab.OVERVIEW).performClick()
        compose.onNodeWithTag("input-OVERVIEW").assertTextEquals("keep color draft")
    }
}
