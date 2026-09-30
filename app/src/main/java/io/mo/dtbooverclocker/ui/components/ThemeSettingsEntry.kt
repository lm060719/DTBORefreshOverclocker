package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun ThemeSettingsEntry(onClick: () -> Unit) {
    val strings = I18n.current
    val modifier = Modifier.testTag("theme-settings-entry")
    if (AppTheme.isMiuix) {
        AppCard(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = strings.settingsTheme,
                summary = strings.settingsThemeDesc,
                modifier = modifier,
                startAction = { PreferenceIcon(Icons.Rounded.Palette) },
                onClick = onClick
            )
        }
    } else {
        NavigationEntry(Icons.Rounded.Palette, strings.settingsTheme, strings.settingsThemeDesc, onClick, modifier)
    }
}
