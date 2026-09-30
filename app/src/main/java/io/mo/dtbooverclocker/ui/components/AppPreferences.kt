package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.AppLanguage
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.i18n.I18n
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PreferenceIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.padding(end = 6.dp), tint = MiuixTheme.colorScheme.onBackground)
}

@Composable
internal fun UiStylePreference(selected: UiStyle, onSelect: (UiStyle) -> Unit) {
    val strings = I18n.current
    OverlayDropdownPreference(
        modifier = Modifier.testTag("ui-style-selector"),
        title = strings.settingsAppearance,
        summary = strings.settingsAppearanceDesc,
        items = UiStyle.entries.map { it.label },
        selectedIndex = selected.ordinal,
        startAction = { PreferenceIcon(Icons.Rounded.Palette) },
        onSelectedIndexChange = { onSelect(UiStyle.entries[it]) }
    )
}

@Composable
internal fun LanguagePreference(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    val strings = I18n.current
    val options = listOf(AppLanguage.FOLLOW_SYSTEM, AppLanguage.ENGLISH, AppLanguage.CHINESE)
    OverlayDropdownPreference(
        modifier = Modifier.testTag("language-selector"),
        title = strings.settingsLanguage,
        summary = strings.settingsLanguageDesc,
        items = listOf(strings.langFollowSystem, strings.langEnglish, strings.langChinese),
        selectedIndex = options.indexOf(selected),
        startAction = { PreferenceIcon(Icons.Rounded.Language) },
        onSelectedIndexChange = { onSelect(options[it]) }
    )
}
