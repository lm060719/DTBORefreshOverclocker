package io.mo.dtbooverclocker.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TRANSITION_MILLIS = 320

/**
 * 二级页转场。[predictiveBack] 开启时由这里接管返回：页面跟随返回手势滑动，松手完成返回，
 * 取消则滑回原位；此时各页面自身的 BackHandler 应关闭，否则会抢先消费返回事件。
 */
@Composable
internal fun AppScreenTransition(
    currentScreen: AppScreen,
    predictiveBack: Boolean = false,
    onBack: () -> Unit = {},
    content: @Composable (screen: AppScreen, isActive: Boolean) -> Unit
) {
    val screenStateHolder = rememberSaveableStateHolder()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val transitionState = remember { SeekableTransitionState(currentScreen) }
    val transition = rememberTransition(transitionState, label = "app-screen-navigation")
    val scope = rememberCoroutineScope()
    var gestureActive by remember { mutableStateOf(false) }
    val latestOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(currentScreen) {
        if (!gestureActive) transitionState.animateTo(currentScreen)
    }

    val backTarget = currentScreen.parent
    PredictiveBackHandler(enabled = predictiveBack && backTarget != null) { progress ->
        val target = backTarget ?: return@PredictiveBackHandler
        try {
            progress.collect { event ->
                gestureActive = true
                transitionState.seekTo(event.progress, target)
            }
            gestureActive = false
            latestOnBack()
        } catch (e: CancellationException) {
            gestureActive = false
            // 取消手势：从当前进度滑回起点，再落定到原页面。
            scope.launch {
                val settle = Animatable(transitionState.fraction)
                settle.animateTo(0f, tween((settle.value * TRANSITION_MILLIS).toInt())) {
                    scope.launch { transitionState.seekTo(value) }
                }
                transitionState.snapTo(currentScreen)
            }
            throw e
        }
    }

    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val returning = targetState.depth < initialState.depth
            // 手势拖动时进度直接对应位移，避免缓动曲线让页面"超前"手指。
            val easing = if (gestureActive) LinearEasing else FastOutSlowInEasing
            val motion = tween<IntOffset>(TRANSITION_MILLIS, easing = easing)
            val fade = tween<Float>(TRANSITION_MILLIS, easing = easing)
            val enter = slideInHorizontally(motion) { width ->
                direction * if (returning) -width / 4 else width
            } + fadeIn(fade, initialAlpha = if (returning) 0.85f else 1f)
            val exit = slideOutHorizontally(motion) { width ->
                direction * if (returning) width else -width / 4
            } + fadeOut(fade, targetAlpha = if (returning) 1f else 0.85f)
            (enter togetherWith exit).apply {
                // Keep the child above its parent while it slides off on back navigation.
                targetContentZIndex = targetState.depth.toFloat()
            }.using(null)
        }
    ) { screen ->
        screenStateHolder.SaveableStateProvider(screen.name) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                content(screen, screen == currentScreen)
            }
        }
    }
}

/** 返回时的上一级页面；主页没有上一级。 */
internal val AppScreen.parent: AppScreen?
    get() = when (this) {
        AppScreen.MAIN -> null
        AppScreen.THEME_SETTINGS -> AppScreen.SETTINGS
        else -> AppScreen.MAIN
    }

private val AppScreen.depth: Int
    get() = when (this) {
        AppScreen.MAIN -> 0
        AppScreen.THEME_SETTINGS -> 2
        else -> 1
    }
