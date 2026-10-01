package io.mo.dtbooverclocker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.dtbooverclocker.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// 与 res/drawable/ic_launcher.xml 同一套 108×108 视口与配色，开屏即逐笔"画出"图标。
private const val VIEWPORT = 108f
private val BadgeColor = Color(0xFF0F172A)
private val RingColor = Color(0xFF1E293B)
private val FrameColor = Color(0xFF38BDF8)
private val PulseColor = Color(0xFF34D399)
private val BoltColor = Color(0xFFFBBF24)
private val OvershootEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

private fun svgPath(data: String): Path = PathParser().parsePathString(data).toPath()

private class SplashPaths {
    val badge = svgPath("M54,6 C27.49,6 6,27.49 6,54 C6,80.51 27.49,102 54,102 C80.51,102 102,80.51 102,54 C102,27.49 80.51,6 54,6 Z")
    val ring = svgPath("M54,12 C30.8,12 12,30.8 12,54 C12,77.2 30.8,96 54,96 C77.2,96 96,77.2 96,54 C96,30.8 77.2,12 54,12 Z")
    val frame = svgPath("M26,26 L82,26 A4,4 0 0,1 86,30 L86,66 A4,4 0 0,1 82,70 L26,70 A4,4 0 0,1 22,66 L22,30 A4,4 0 0,1 26,26 Z")
    // PathMeasure 只量第一段轮廓，支架拆成两条分别裁剪。
    val stem = svgPath("M54,70 L54,82")
    val baseLeft = svgPath("M54,82 L38,82")
    val baseRight = svgPath("M54,82 L70,82")
    val pulse = svgPath("M28,48 L38,48 L44,36 L52,58 L60,40 L66,48 L80,48")
    val bolt = svgPath("M56,31 L48,46 L55,46 L51,61 L62,44 L55,44 Z")
    val measure = PathMeasure()
    val segment = Path()
}

/**
 * 冷启动开屏：徽章弹出 → 屏幕边框描线 → 刷新波形扫过 → 闪电点亮 → 整体淡出露出主界面。
 * 系统 SplashScreen 只铺同色背景（见 Theme.DTBORefreshOverclocker.Starting），这里无缝接管。
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val currentOnFinished by rememberUpdatedState(onFinished)
    val paths = remember { SplashPaths() }
    val badge = remember { Animatable(0f) }
    val frame = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val bolt = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { badge.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f)) }
            launch { frame.animateTo(1f, tween(380, delayMillis = 120, easing = FastOutSlowInEasing)) }
            launch { pulse.animateTo(1f, tween(400, delayMillis = 380, easing = LinearOutSlowInEasing)) }
            launch { bolt.animateTo(1f, tween(280, delayMillis = 680, easing = OvershootEasing)) }
            launch { title.animateTo(1f, tween(360, delayMillis = 480, easing = FastOutSlowInEasing)) }
        }
        exit.animateTo(1f, tween(280, delayMillis = 140, easing = FastOutLinearInEasing))
        currentOnFinished()
    }

    val textColor = colorResource(R.color.splash_text)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(colorResource(R.color.splash_background))
            // 动画期间吞掉触摸，避免误触到下面的主界面。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        val s = (0.6f + 0.4f * badge.value) * (1f + 0.1f * exit.value)
                        scaleX = s
                        scaleY = s
                        alpha = badge.value.coerceIn(0f, 1f)
                    }
            ) {
                scale(size.minDimension / VIEWPORT, pivot = Offset.Zero) {
                    drawIcon(paths, frame.value, pulse.value, bolt.value)
                }
            }
            Text(
                text = stringResource(R.string.app_name),
                color = textColor,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
                modifier = Modifier.graphicsLayer {
                    alpha = title.value
                    translationY = (1f - title.value) * 12.dp.toPx()
                }
            )
        }
    }
}

private fun DrawScope.drawIcon(paths: SplashPaths, frame: Float, pulse: Float, bolt: Float) {
    drawPath(paths.badge, BadgeColor)
    drawPath(paths.ring, RingColor, style = Stroke(width = 2f))

    val line = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(paths.frame, RingColor, alpha = frame)
    drawTrimmed(paths, paths.frame, frame, FrameColor, line)
    // 支架在边框画到一半后才开始长出。
    val stand = ((frame - 0.5f) / 0.5f).coerceIn(0f, 1f)
    drawTrimmed(paths, paths.stem, stand, FrameColor, line)
    drawTrimmed(paths, paths.baseLeft, stand, FrameColor, line)
    drawTrimmed(paths, paths.baseRight, stand, FrameColor, line)

    drawTrimmed(paths, paths.pulse, pulse, PulseColor, Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    if (bolt > 0f) {
        withTransform({ scale(bolt, bolt, pivot = Offset(55f, 46f)) }) {
            drawPath(paths.bolt, BoltColor, alpha = bolt.coerceIn(0f, 1f))
        }
    }
}

private fun DrawScope.drawTrimmed(paths: SplashPaths, path: Path, fraction: Float, color: Color, stroke: Stroke) {
    if (fraction <= 0f) return
    val measure = paths.measure
    measure.setPath(path, false)
    paths.segment.reset()
    measure.getSegment(0f, measure.length * fraction.coerceAtMost(1f), paths.segment, true)
    drawPath(paths.segment, color, style = stroke)
}
