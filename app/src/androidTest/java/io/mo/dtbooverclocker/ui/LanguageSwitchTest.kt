package io.mo.dtbooverclocker.ui

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.mo.dtbooverclocker.model.AppLanguage
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.i18n.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LanguageSwitchTest(private val style: UiStyle) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun styles() = UiStyle.entries.map { arrayOf(it) }
    }

    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun languageChangesKeepTheActivityAndSettingsPage() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
        val keys = listOf("ui_style", "app_language", "disclaimer_accepted")
        val previous = keys.associateWith { prefs.all[it] }
        val localeManager = if (Build.VERSION.SDK_INT >= 33) {
            application.getSystemService(LocaleManager::class.java)
        } else null
        val previousLocales = localeManager?.applicationLocales
        try {
            prefs.edit().putString("ui_style", style.code).putString("app_language", "zh")
                .putBoolean("disclaimer_accepted", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNode(hasText("设置") and hasClickAction()).performClick()
                compose.onNodeWithTag("settings-hub-list").performScrollToNode(hasText("高级设置"))
                compose.onNode(hasText("高级设置") and hasClickAction()).performClick()
                compose.onNodeWithTag("advanced-settings-screen").assertIsDisplayed()
                compose.waitForIdle()
                var originalActivity: MainActivity? = null
                scenario.onActivity { originalActivity = it }

                fun select(language: AppLanguage, label: String) {
                    if (style == UiStyle.MIUIX) {
                        compose.onNodeWithTag("language-selector").performClick()
                        compose.onNode(hasText(label) and hasClickAction()).performClick()
                    } else {
                        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
                    }
                    if (localeManager != null) {
                        val expectedLocales = if (language == AppLanguage.FOLLOW_SYSTEM) {
                            LocaleList.getEmptyLocaleList()
                        } else LocaleList.forLanguageTags(language.code)
                        compose.waitUntil(5_000) { localeManager.applicationLocales == expectedLocales }
                    }
                    compose.waitForIdle()
                    compose.onNodeWithTag("advanced-settings-screen").assertIsDisplayed()
                    compose.onNodeWithText(I18n.getStrings(language).settingsLanguage).assertIsDisplayed()
                    scenario.onActivity { assertSame("Language switching must not recreate the activity", originalActivity, it) }
                    assertEquals(language.code, prefs.getString("app_language", null))
                }

                select(AppLanguage.ENGLISH, "英文")
                select(AppLanguage.CHINESE, "Chinese")
                select(AppLanguage.FOLLOW_SYSTEM, "跟随系统")
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
            if (previousLocales != null) localeManager.applicationLocales = previousLocales
        }
    }
}
