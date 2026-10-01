package io.mo.dtbooverclocker.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.components.supportsLiquidGlass
import io.mo.dtbooverclocker.ui.components.supportsPredictiveBack
import io.mo.dtbooverclocker.ui.theme.supportsMonet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ThemeSettingsNavigationTest(private val initialStyle: UiStyle) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun styles() = UiStyle.entries.map { arrayOf(it) }
    }

    @get:Rule val compose = createEmptyComposeRule()

    private fun assertAppearanceIsInSubpageOnly() {
        listOf("ui-style-selector", "ui-style-miuix", "monet-colors", "floating-bottom-bar", "liquid-glass", "predictive-back").forEach {
            compose.onNodeWithTag(it, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun openThemeFromAdvancedSettings() {
        compose.onNodeWithTag("settings-hub-list").performScrollToNode(hasText("高级设置"))
        compose.onNode(hasText("高级设置") and hasClickAction()).performClick()
        compose.onNodeWithTag("advanced-settings-screen").assertIsDisplayed()
        assertAppearanceIsInSubpageOnly()
        compose.onNodeWithTag("theme-settings-entry").performClick()
        compose.onNodeWithTag("theme-settings-screen").assertIsDisplayed()
    }

    @Test
    fun themePageKeepsChangesAndReturnsToItsEntryAfterRecreation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
        val keys = listOf("ui_style", "app_language", "disclaimer_accepted", "monet_colors", "floating_bottom_bar", "liquid_glass", "predictive_back")
        val previous = keys.associateWith { prefs.all[it] }
        try {
            prefs.edit().putString("ui_style", initialStyle.code).putString("app_language", "zh")
                .putBoolean("disclaimer_accepted", true).putBoolean("monet_colors", false)
                .putBoolean("floating_bottom_bar", false).putBoolean("liquid_glass", false).putBoolean("predictive_back", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNode(hasText("设置") and hasClickAction()).performClick()
                assertAppearanceIsInSubpageOnly()
                // 主题入口只在高级设置里，设置页不再重复显示。
                compose.onNodeWithTag("theme-settings-entry").assertDoesNotExist()
                openThemeFromAdvancedSettings()
                if (initialStyle == UiStyle.MIUIX) {
                    compose.onNodeWithTag("ui-style-selector").performClick()
                    compose.onNode(hasText("Material 3") and hasClickAction()).performClick()
                } else {
                    compose.onNodeWithTag("ui-style-miuix").performClick()
                }
                compose.onNodeWithTag("theme-settings-screen").assertIsDisplayed()
                if (supportsMonet()) compose.onNodeWithTag("monet-colors").performClick().assertIsOn()
                compose.onNodeWithTag("floating-bottom-bar").performScrollTo().performClick().assertIsOn()
                if (supportsLiquidGlass()) compose.onNodeWithTag("liquid-glass").performScrollTo().performClick().assertIsOn()
                if (supportsPredictiveBack()) compose.onNodeWithTag("predictive-back").performScrollTo().assertIsOn().performClick().assertIsOff()
                scenario.recreate()
                compose.onNodeWithTag("theme-settings-screen").assertIsDisplayed()
                compose.onNodeWithTag("floating-bottom-bar").performScrollTo().assertIsOn()
                if (supportsMonet()) compose.onNodeWithTag("monet-colors").performScrollTo().assertIsOn()
                if (supportsPredictiveBack()) compose.onNodeWithTag("predictive-back").performScrollTo().assertIsOff()
                compose.onNodeWithContentDescription("返回设置").performClick()
                compose.onNodeWithTag("advanced-settings-screen").assertIsDisplayed()
                assertAppearanceIsInSubpageOnly()
                compose.onNodeWithTag("theme-settings-entry").performClick()
                compose.onNodeWithTag("floating-bottom-bar").performScrollTo().assertIsOn()
                if (supportsLiquidGlass()) compose.onNodeWithTag("liquid-glass").performScrollTo().assertIsOn()
                scenario.recreate()
                compose.onNodeWithTag("theme-settings-screen").assertIsDisplayed()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("advanced-settings-screen").assertIsDisplayed()
                compose.onNodeWithTag("theme-settings-entry").assertIsDisplayed()
                assertAppearanceIsInSubpageOnly()
            }
        } finally {
            prefs.edit().apply {
                previous.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
        }
    }
}
