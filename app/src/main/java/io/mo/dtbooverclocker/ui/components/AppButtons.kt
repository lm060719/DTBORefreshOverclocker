package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonColors as MiuixButtonColors
import io.mo.dtbooverclocker.ui.theme.AppTheme

/** Adapt existing action colors, including destructive actions, to Miuix controls. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = if (AppTheme.isMiuix) PaddingValues(horizontal = 16.dp, vertical = 12.dp) else ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (AppTheme.isMiuix) ActionButton(onClick, modifier, enabled, colors, contentPadding, content)
    else androidx.compose.material3.Button(onClick, modifier, enabled, colors = colors, contentPadding = contentPadding, content = content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = if (AppTheme.isMiuix) PaddingValues(horizontal = 16.dp, vertical = 12.dp) else ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (AppTheme.isMiuix) ActionButton(onClick, modifier, enabled, colors, contentPadding, content)
    else androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled, colors = colors, contentPadding = contentPadding, content = content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = if (AppTheme.isMiuix) PaddingValues(horizontal = 16.dp, vertical = 12.dp) else ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (!AppTheme.isMiuix) {
        androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, colors = colors, contentPadding = contentPadding, content = content)
        return
    }
    ActionButton(
        onClick, modifier, enabled,
        colors.copy(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        contentPadding, content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    if (AppTheme.isMiuix) ActionButton(onClick, modifier, enabled, colors, contentPadding, content)
    else androidx.compose.material3.TextButton(onClick, modifier, enabled, colors = colors, contentPadding = contentPadding, content = content)
}

@Composable
private fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: ButtonColors,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit
) {
    val contentColor = if (enabled) colors.contentColor else colors.disabledContentColor
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = 16.dp,
        minHeight = 48.dp,
        insideMargin = contentPadding,
        colors = MiuixButtonColors(
            color = colors.containerColor,
            disabledColor = colors.disabledContainerColor,
            contentColor = colors.contentColor,
            disabledContentColor = colors.disabledContentColor
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}
