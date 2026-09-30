package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.components.AppCard
import io.mo.dtbooverclocker.ui.components.Card
import io.mo.dtbooverclocker.ui.components.IconButton
import io.mo.dtbooverclocker.ui.components.OutlinedCard
import io.mo.dtbooverclocker.ui.components.Slider
import io.mo.dtbooverclocker.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppControlsTest {
    @get:Rule val compose = createComposeRule()
    private var style by mutableStateOf(UiStyle.MATERIAL)
    private var enabled by mutableStateOf(true)
    private var caption by mutableStateOf("First caption")
    private var clicks = 0

    @Test
    fun draftsInsideAllCardTypesSurviveStyleChangesAndRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppTheme(uiStyle = style) {
                Column {
                    AppCard { Draft("section"); Text(caption) }
                    Card { Draft("filled") }
                    OutlinedCard { Draft("outlined") }
                }
            }
        }
        for (tag in listOf("section", "filled", "outlined")) {
            compose.onNodeWithTag(tag).performTextInput("draft-$tag")
        }
        for (selected in listOf(UiStyle.MIUIX, UiStyle.MATERIAL, UiStyle.MIUIX)) {
            compose.runOnIdle { style = selected; caption = selected.label }
            compose.onNodeWithText(selected.label).assertExists()
            for (tag in listOf("section", "filled", "outlined")) {
                compose.onNodeWithTag(tag).assertTextEquals("draft-$tag")
            }
        }
        restoration.emulateSavedInstanceStateRestore()
        for (tag in listOf("section", "filled", "outlined")) {
            compose.onNodeWithTag(tag).assertTextEquals("draft-$tag")
        }
    }

    @Test
    fun busyControlsRemainDisabledInBothStyles() {
        compose.setContent {
            AppTheme(uiStyle = style) {
                Column {
                    IconButton({ clicks++ }, Modifier.testTag("refresh"), enabled) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    OutlinedCard({ clicks++ }, Modifier.testTag("entry"), enabled) { Text("Open entry") }
                }
            }
        }
        for (selected in UiStyle.entries) {
            compose.runOnIdle { style = selected; enabled = true }
            compose.onNodeWithTag("refresh").performClick()
            compose.onNodeWithTag("entry").performClick()
            compose.runOnIdle { enabled = false }
            compose.onNodeWithTag("refresh").assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("entry").assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals(4, clicks) }
    }

    @Test
    fun sliderKeepsItsRangeAndValueWhenStyleChanges() {
        var value by mutableStateOf(120f)
        compose.setContent {
            AppTheme(uiStyle = style) {
                Slider(value, { value = it }, Modifier.testTag("refresh-rate"), enabled, 30f..240f)
            }
        }
        for (selected in UiStyle.entries) {
            compose.runOnIdle { style = selected; enabled = true }
            compose.onNodeWithTag("refresh-rate").performSemanticsAction(SemanticsActions.SetProgress) { it(165f) }
            compose.runOnIdle { assertEquals(165f, value) }
            compose.runOnIdle { enabled = false }
            compose.onNodeWithTag("refresh-rate").assertIsNotEnabled()
            if (selected == UiStyle.MIUIX) {
                compose.onNodeWithTag("refresh-rate").performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
                compose.runOnIdle { assertEquals(165f, value) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Draft(tag: String) {
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(text, { text = it }, Modifier.testTag(tag))
    }
}
