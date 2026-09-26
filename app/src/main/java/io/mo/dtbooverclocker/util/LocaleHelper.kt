package io.mo.dtbooverclocker.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.mo.dtbooverclocker.model.AppLanguage
import java.util.Locale

object LocaleHelper {
    fun getEffectiveLocale(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.FOLLOW_SYSTEM -> {
                val sysLocale = try {
                    Resources.getSystem().configuration.locales[0]
                } catch (_: Throwable) {
                    Locale.getDefault()
                } ?: Locale.getDefault()
                if (sysLocale.language.equals("zh", ignoreCase = true)) {
                    Locale.SIMPLIFIED_CHINESE
                } else {
                    Locale.ENGLISH
                }
            }
        }
    }

    fun applyLocale(context: Context, language: AppLanguage): Context {
        val targetLocale = getEffectiveLocale(language)
        Locale.setDefault(targetLocale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(targetLocale)
        return context.createConfigurationContext(config)
    }

    fun updateSystemLocale(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                val localeList = when (language) {
                    AppLanguage.FOLLOW_SYSTEM -> LocaleList.getEmptyLocaleList()
                    AppLanguage.ENGLISH -> LocaleList.forLanguageTags("en")
                    AppLanguage.CHINESE -> LocaleList.forLanguageTags("zh")
                }
                localeManager?.applicationLocales = localeList
            } catch (_: Throwable) {
                // Ignore if permission or system restriction prevents setting application locales
            }
        }
    }
}
