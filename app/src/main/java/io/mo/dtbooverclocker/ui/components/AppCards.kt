package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.theme.AppTheme
import top.yukonga.miuix.kmp.squircle.addSquircleRect

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = AdaptiveCard(
    modifier, MaterialTheme.shapes.large, CardDefaults.cardColors(containerColor = containerColor),
    border = null, outlined = false, onClick = onClick, enabled = true, content = content
)

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) = AdaptiveCard(modifier, shape, colors, border, outlined = false, onClick = null, enabled = true, content)

@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    border: BorderStroke = CardDefaults.outlinedCardBorder(),
    content: @Composable ColumnScope.() -> Unit
) = AdaptiveCard(modifier, shape, colors, border, outlined = true, onClick = null, enabled = true, content)

@Composable
fun OutlinedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    border: BorderStroke = CardDefaults.outlinedCardBorder(enabled),
    content: @Composable ColumnScope.() -> Unit
) = AdaptiveCard(modifier, shape, colors, border, outlined = true, onClick, enabled, content)

@Composable
private fun AdaptiveCard(
    modifier: Modifier,
    shape: Shape,
    colors: CardColors,
    border: BorderStroke?,
    outlined: Boolean,
    onClick: (() -> Unit)?,
    enabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val currentContent = rememberUpdatedState(content)
    // Carry drafts, scroll positions, and remembered state across the two card implementations.
    val cardContent = remember {
        movableContentOf<ColumnScope> { scope -> currentContent.value(scope) }
    }
    if (AppTheme.isMiuix) {
        val density = LocalDensity.current
        val radius = with(density) {
            (shape as? CornerBasedShape)?.topStart?.toPx(Size.Zero, density)?.toDp() ?: 24.dp
        }
        val containerColor = if (enabled) colors.containerColor else colors.disabledContainerColor
        val contentColor = if (enabled) colors.contentColor else colors.disabledContentColor
        val decoratedModifier = modifier
            .then(if (border != null) Modifier.drawWithCache {
                val strokeWidth = border.width.toPx()
                val inset = strokeWidth / 2
                val path = Path().apply {
                    addSquircleRect(
                        width = (size.width - strokeWidth).coerceAtLeast(0f),
                        height = (size.height - strokeWidth).coerceAtLeast(0f),
                        cornerRadius = (radius.toPx() - inset).coerceAtLeast(0f)
                    )
                }
                onDrawWithContent {
                    drawContent()
                    translate(inset, inset) { drawPath(path, border.brush, style = Stroke(strokeWidth)) }
                }
            } else Modifier)
            .then(if (onClick != null) Modifier.semantics {
                role = Role.Button
                if (!enabled) disabled()
            } else Modifier)
        top.yukonga.miuix.kmp.basic.Card(
            modifier = decoratedModifier,
            cornerRadius = radius,
            insideMargin = PaddingValues(0.dp),
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(containerColor, contentColor),
            showIndication = onClick != null && enabled,
            onClick = onClick?.takeIf { enabled }
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { cardContent(this) }
        }
    } else if (outlined && onClick != null) {
        androidx.compose.material3.OutlinedCard(
            onClick, modifier, enabled, shape, colors = colors, border = border ?: CardDefaults.outlinedCardBorder(enabled)
        ) { cardContent(this) }
    } else if (outlined) {
        androidx.compose.material3.OutlinedCard(
            modifier, shape, colors = colors, border = border ?: CardDefaults.outlinedCardBorder()
        ) { cardContent(this) }
    } else if (onClick != null) {
        androidx.compose.material3.Card(onClick, modifier, enabled, shape, colors = colors, border = border) { cardContent(this) }
    } else {
        androidx.compose.material3.Card(modifier, shape, colors = colors, border = border) { cardContent(this) }
    }
}
