package io.mo.dtbooverclocker.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.dtbooverclocker.ui.components.liquid.lens
import io.mo.dtbooverclocker.ui.components.liquid.rememberCombinedBackdrop
import io.mo.dtbooverclocker.ui.components.liquid.vibrancy
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun supportsLiquidGlass(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRuntimeShaderSupported()

/** Real backdrop sampling and refraction; the content layer never includes the bar itself. */
@Composable
internal fun FloatingStudioBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    glass: Boolean,
    backdrop: Backdrop
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val surface = scheme.surfaceContainerLow
    val foreground = scheme.onSurface
    val glassActive = glass && supportsLiquidGlass()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val sources = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    val pressed = sources.map { it.collectIsPressedAsState().value }.any { it }
    var dragIndex by remember { mutableStateOf<Float?>(null) }
    val position by animateFloatAsState(
        dragIndex ?: selectedIndex.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f), label = "floating-tab"
    )
    val pressure by animateFloatAsState(
        if (enabled && (pressed || dragIndex != null)) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = 400f), label = "glass-pressure"
    )
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val highlight = if (glassActive) rememberGlassHighlight() else rememberUpdatedState(Highlight.Default)
    val container = if (glassActive) surface.copy(alpha = 0.4f) else surface

    BoxWithConstraints(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp)
    ) {
        val tabWidth = (maxWidth - 8.dp) / items.size
        val logicalPosition = position.coerceIn(0f, items.lastIndex.toFloat())
        val displayPosition = if (isLtr) logicalPosition else items.lastIndex - logicalPosition
        val panelEffect = if (glassActive) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    padding = maxOf(padding, 40.dp.toPx())
                    vibrancy()
                    blur(4.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                highlight = { highlight.value.copy(alpha = 0.75f) },
                onDrawSurface = { drawRect(container) }
            )
        } else Modifier.background(container, CircleShape)

        Box(
            Modifier.fillMaxWidth().height(64.dp)
                .testTag("floating-navigation")
                .graphicsLayer {
                    scaleX = 1f + 0.025f * pressure
                    scaleY = 1f + 0.05f * pressure
                }
                .dropShadow(CircleShape, Shadow(radius = 10.dp, color = Color.Black, alpha = if (dark) 0.2f else 0.1f))
                .then(panelEffect)
                .pointerInput(enabled, isLtr, items.size, tabWidth) {
                    if (!enabled) return@pointerInput
                    fun indexAt(x: Float): Float {
                        val logicalX = if (isLtr) x else size.width - x
                        return ((logicalX - 4.dp.toPx()) / tabWidth.toPx() - 0.5f)
                            .coerceIn(0f, items.lastIndex.toFloat())
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { dragIndex = indexAt(it.x) },
                        onHorizontalDrag = { change, _ -> change.consume(); dragIndex = indexAt(change.position.x) },
                        onDragCancel = { dragIndex = null },
                        onDragEnd = {
                            dragIndex?.roundToInt()?.let(onSelect)
                            dragIndex = null
                        }
                    )
                }
        ) {
            if (!glassActive) {
                Box(
                    Modifier.padding(4.dp).offset(x = tabWidth * displayPosition)
                        .width(tabWidth).height(56.dp).background(accent.copy(alpha = 0.15f), CircleShape)
                )
            }
            Row(Modifier.fillMaxWidth().height(64.dp).padding(4.dp).selectableGroup()) {
                items.forEachIndexed { index, item ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .selectable(
                                selected = selectedIndex == index,
                                enabled = enabled,
                                role = Role.Tab,
                                interactionSource = sources[index],
                                indication = null,
                                onClick = { onSelect(index) }
                            ),
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        FloatingTabContent(item, if (!enabled) foreground.copy(alpha = 0.38f)
                            else if (!glassActive && selectedIndex == index) accent else foreground)
                    }
                }
            }
            if (glassActive) {
                // Record an accent-colored visual pass, without duplicate click or accessibility targets.
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .clearAndSetSemantics {}.alpha(0f).layerBackdrop(tabsBackdrop)
                        .then(panelEffect).padding(4.dp)
                ) {
                    items.forEach { item ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) { FloatingTabContent(item, if (enabled) accent else foreground.copy(alpha = 0.38f)) }
                    }
                }
                Box(
                    Modifier.padding(4.dp).offset(x = tabWidth * displayPosition)
                        .width(tabWidth).height(56.dp).testTag("glass-indicator")
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { CircleShape },
                            effects = {
                                lens(10.dp.toPx() * pressure, 14.dp.toPx() * pressure,
                                    depthEffect = true, chromaticAberration = 0.5f)
                            },
                            highlight = { highlight.value.copy(alpha = pressure) },
                            layerBlock = {
                                scaleX = 1f + pressure * 0.18f
                                scaleY = 1f + pressure * 0.12f
                            },
                            onDrawSurface = {
                                drawRect((if (dark) Color.White else Color.Black).copy(alpha = 0.1f * (1f - pressure)))
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun FloatingTabContent(item: NavigationItem, color: Color) {
    Icon(item.icon, null, Modifier.size(24.dp), tint = color)
    Text(item.label, color = color, fontSize = 11.sp, lineHeight = 14.sp,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/** Read sensor state during drawing, so tilt does not recompose the navigation or pages. */
@Composable
private fun rememberGlassHighlight(): androidx.compose.runtime.State<Highlight> {
    val tilt = rememberDeviceTilt()
    return remember(tilt) {
        derivedStateOf {
            val gravity = tilt.value
            val angle = if (gravity.gravityX * gravity.gravityX + gravity.gravityY * gravity.gravityY > 0.01f)
                (atan2(gravity.gravityY, gravity.gravityX) / (PI / 60)).roundToInt() * (PI / 60)
            else -PI / 2
            Highlight(width = 1.dp, style = BloomStroke(
                color = Color.White.copy(alpha = 0.12f),
                innerBlurRadius = 2.dp,
                primaryLight = LightSource(LightPosition(0.5f + cos(angle).toFloat(), 0.7f + sin(angle).toFloat(), -0.05f), Color.White, 1f),
                secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f), Color.White, 0.4f),
                dualPeak = true
            ))
        }
    }
}
