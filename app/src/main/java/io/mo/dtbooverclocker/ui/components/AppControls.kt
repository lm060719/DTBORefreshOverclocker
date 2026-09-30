package io.mo.dtbooverclocker.ui.components

import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.theme.AppTheme

@Composable
fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (AppTheme.isMiuix) top.yukonga.miuix.kmp.basic.RadioButton(selected, onClick, modifier)
    else androidx.compose.material3.RadioButton(selected, onClick, modifier)
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (AppTheme.isMiuix) top.yukonga.miuix.kmp.basic.Switch(checked, onCheckedChange, modifier, enabled = enabled)
    else androidx.compose.material3.Switch(checked, onCheckedChange, modifier, enabled = enabled)
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) {
    if (AppTheme.isMiuix) {
        top.yukonga.miuix.kmp.basic.IconButton(
            onClick = onClick,
            modifier = modifier.semantics {
                role = Role.Button
                if (!enabled) disabled()
            },
            enabled = enabled,
            backgroundColor = if (enabled) colors.containerColor else colors.disabledContainerColor,
            minWidth = 48.dp,
            minHeight = 48.dp
        ) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor) { content() }
        }
    } else {
        androidx.compose.material3.IconButton(onClick, modifier, enabled, colors = colors, content = content)
    }
}

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    if (AppTheme.isMiuix) {
        top.yukonga.miuix.kmp.basic.Slider(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            modifier = modifier.semantics { if (!enabled) disabled() },
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished?.takeIf { enabled }
        )
    } else {
        androidx.compose.material3.Slider(
            value, onValueChange, modifier, enabled, valueRange = valueRange, steps = steps,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}
