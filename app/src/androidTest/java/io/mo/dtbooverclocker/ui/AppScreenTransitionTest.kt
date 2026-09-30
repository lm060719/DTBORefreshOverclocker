package io.mo.dtbooverclocker.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppScreenTransitionTest(private val style: UiStyle, private val parent: AppScreen) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, parent={1}")
        fun cases() = UiStyle.entries.flatMap { style ->
            listOf(AppScreen.MAIN, AppScreen.SETTINGS).map { parent -> arrayOf(style, parent) }
        }
    }

    @get:Rule val compose = createComposeRule()
    private val current = mutableStateOf(AppScreen.THEME_SETTINGS)
    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun showScreens() {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AppTheme(uiStyle = style) {
                AppScreenTransition(current.value) { screen, isActive ->
                    BackHandler(enabled = isActive && screen != AppScreen.MAIN) {
                        current.value = if (screen == AppScreen.THEME_SETTINGS) parent else AppScreen.MAIN
                    }
                    Box(Modifier.fillMaxSize().testTag(screen.name))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun back() {
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun returningKeepsBothScreensUntilTheChildSlidesOut() {
        showScreens()
        val width = compose.onNodeWithTag(AppScreen.THEME_SETTINGS.name).fetchSemanticsNode().boundsInRoot.width
        back()
        compose.mainClock.advanceTimeBy(80)
        val childX = compose.onNodeWithTag(AppScreen.THEME_SETTINGS.name).fetchSemanticsNode().positionInRoot.x
        val parentX = compose.onNodeWithTag(parent.name).fetchSemanticsNode().positionInRoot.x
        assertTrue("Child should be sliding right", childX > 0f && childX < width)
        assertTrue("Parent should be entering from the left", parentX < 0f)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag(AppScreen.THEME_SETTINGS.name).assertDoesNotExist()
        compose.onNodeWithTag(parent.name).assertExists()
        assertEquals(0f, compose.onNodeWithTag(parent.name).fetchSemanticsNode().boundsInRoot.left, 1f)
    }

    @Test
    fun outgoingThemeDoesNotInterceptAnotherBackDuringTheTransition() {
        if (parent != AppScreen.SETTINGS) return
        showScreens()
        back()
        back()
        compose.runOnIdle { assertEquals(AppScreen.MAIN, current.value) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag(AppScreen.MAIN.name).assertExists()
        compose.onNodeWithTag(AppScreen.SETTINGS.name).assertDoesNotExist()
        compose.onNodeWithTag(AppScreen.THEME_SETTINGS.name).assertDoesNotExist()
    }
}
