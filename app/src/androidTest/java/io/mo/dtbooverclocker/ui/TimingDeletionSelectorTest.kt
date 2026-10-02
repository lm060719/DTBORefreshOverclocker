package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.mo.dtbooverclocker.model.TimingCandidate
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class TimingDeletionSelectorTest(private val style: UiStyle) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun styles() = UiStyle.entries.map { arrayOf(it) }
    }
    @get:Rule val compose = createComposeRule()
    private fun candidate(index: Int, entry: Int = 1) = TimingCandidate("$entry:$index", entry, File("$entry.dts"),
        "/test_panel/display-timings/timing@$index", 0, 1, listOf(60, 90, 120)[index])
    private val modes = (0..2).map { candidate(it) }
    private var anchor by mutableStateOf(modes.first())
    private var busy by mutableStateOf(false)
    private var deleted: Set<String>? = null

    private fun show() {
        compose.setContent {
            AppTheme(uiStyle = style) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TimingDeletionSelector(modes + (0..2).map { candidate(it, 0) }, anchor, busy, true) { deleted = it }
                }
            }
        }
    }

    @Test fun multipleSelectionsRequireConfirmationAndKeepLastMode() {
        show()
        compose.onNodeWithTag("timing-delete-1:0").assertIsOn()
        compose.onNodeWithTag("timing-delete-1:1").performClick().assertIsOn()
        compose.onNodeWithTag("timing-delete-1:2").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("timing-delete-0:0").assertDoesNotExist()
        compose.onNodeWithTag("timing-delete-submit").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithTag("timing-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(setOf("1:0", "1:1"), deleted) }
    }

    @Test fun emptySelectionCannotBeSubmitted() {
        show()
        compose.onNodeWithTag("timing-delete-1:0").performClick().assertIsOff()
        compose.onNodeWithTag("timing-delete-submit").assertIsNotEnabled()
        compose.onNodeWithTag("timing-delete-1:2").performClick().assertIsOn()
        compose.onNodeWithTag("timing-delete-submit").assertIsEnabled()
    }

    @Test fun changingDtbClearsOldSelectionAndConfirmation() {
        show()
        compose.onNodeWithTag("timing-delete-1:1").performClick()
        compose.onNodeWithTag("timing-delete-submit").performClick()
        compose.runOnIdle { anchor = candidate(2, 0) }
        compose.onNodeWithTag("timing-delete-confirm").assertDoesNotExist()
        compose.onNodeWithTag("timing-delete-1:0").assertDoesNotExist()
        compose.onNodeWithTag("timing-delete-0:0").assertIsOff()
        compose.onNodeWithTag("timing-delete-0:2").assertIsOn()
    }

    @Test fun busyStateDisablesSelectionAndSubmit() {
        show()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("timing-delete-1:0").assertIsNotEnabled()
        compose.onNodeWithTag("timing-delete-1:1").assertIsNotEnabled()
        compose.onNodeWithTag("timing-delete-submit").assertIsNotEnabled()
    }
}
