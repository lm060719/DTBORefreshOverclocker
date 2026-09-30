package io.mo.dtbooverclocker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AboutScreenTest(private val style: UiStyle) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun styles() = UiStyle.entries.map { arrayOf(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun aboutContentRendersAndOpensDisclaimer() {
        var checkUpdate = ""
        var viewDisclaimer = ""
        var disclaimerWelcome = ""
        var disclaimerSection = ""
        compose.setContent {
            AppTheme(uiStyle = style) {
                checkUpdate = I18n.current.checkUpdate
                viewDisclaimer = I18n.current.viewFullDisclaimer
                disclaimerWelcome = I18n.current.disclaimerWelcome
                disclaimerSection = I18n.current.disclaimerSec1Title
                AboutScreen(onNavigateBack = {}, onOpenFeedback = {})
            }
        }
        compose.onNodeWithText(checkUpdate).assertIsDisplayed()
        compose.onNodeWithText(viewDisclaimer).performScrollTo().performClick()
        compose.onNodeWithText(disclaimerWelcome).assertIsDisplayed()
        compose.onNodeWithText(disclaimerSection).assertIsDisplayed()
    }
}
