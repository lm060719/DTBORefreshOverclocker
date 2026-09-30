package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing
import io.mo.dtbooverclocker.ui.theme.supportsMonet

@Composable
internal fun MonetPreference(checked: Boolean, onChange: (Boolean) -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        if (AppTheme.isMiuix) {
            MonetPreferenceRow(checked, onChange)
        } else {
            Column(Modifier.padding(Spacing.card)) { MonetPreferenceRow(checked, onChange) }
        }
    }
}

@Composable
internal fun MonetPreferenceRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val strings = I18n.current
    val supported = supportsMonet()
    AppSwitchPreference(
        tag = "monet-colors",
        title = strings.monetColor,
        summary = if (supported) strings.monetColorDesc else strings.monetColorUnsupported,
        icon = Icons.Rounded.Palette,
        checked = checked && supported,
        enabled = supported,
        onChange = onChange
    )
}
