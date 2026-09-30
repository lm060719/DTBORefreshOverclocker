package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun BottomBarPreferences(
    floating: Boolean,
    glass: Boolean,
    onSetFloating: (Boolean) -> Unit,
    onSetGlass: (Boolean) -> Unit
) {
    if (AppTheme.isMiuix) {
        AppCard(Modifier.fillMaxWidth()) { BottomBarPreferenceRows(floating, glass, onSetFloating, onSetGlass) }
    } else {
        SectionCard(title = I18n.current.settingsBottomBar, icon = Icons.Rounded.CallToAction) {
            BottomBarPreferenceRows(floating, glass, onSetFloating, onSetGlass)
        }
    }
}

@Composable
internal fun BottomBarPreferenceRows(
    floating: Boolean,
    glass: Boolean,
    onSetFloating: (Boolean) -> Unit,
    onSetGlass: (Boolean) -> Unit
) {
    val strings = I18n.current
    val supported = supportsLiquidGlass()
    AppSwitchPreference(
        "floating-bottom-bar", strings.floatingBottomBar, strings.floatingBottomBarDesc,
        Icons.Rounded.CallToAction, floating, true, onSetFloating
    )
    AppSwitchPreference(
        "liquid-glass", strings.liquidGlass,
        when {
            !supported -> strings.liquidGlassUnsupported
            !floating -> strings.liquidGlassNeedsFloating
            else -> strings.liquidGlassDesc
        },
        Icons.Rounded.WaterDrop, glass, floating && supported, onSetGlass
    )
}

@Composable
internal fun AppSwitchPreference(
    tag: String,
    title: String,
    summary: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val modifier = Modifier.testTag(tag).semantics {
        toggleableState = ToggleableState(checked)
        if (!enabled) disabled()
    }
    if (AppTheme.isMiuix) {
        SwitchPreference(
            title = title,
            summary = summary,
            modifier = modifier,
            startAction = { PreferenceIcon(icon) },
            checked = checked,
            enabled = enabled,
            onCheckedChange = { if (enabled) onChange(it) }
        )
    } else {
        Row(
            modifier.fillMaxWidth()
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, null, enabled = enabled)
        }
    }
}
