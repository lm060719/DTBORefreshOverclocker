package io.mo.dtbooverclocker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun AppScreenTransition(
    currentScreen: AppScreen,
    content: @Composable (screen: AppScreen, isActive: Boolean) -> Unit
) {
    val screenStateHolder = rememberSaveableStateHolder()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    AnimatedContent(
        targetState = currentScreen,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val returning = targetState.depth < initialState.depth
            val motion = tween<IntOffset>(320, easing = FastOutSlowInEasing)
            val fade = tween<Float>(320, easing = FastOutSlowInEasing)
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
        },
        label = "app-screen-navigation"
    ) { screen ->
        screenStateHolder.SaveableStateProvider(screen.name) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                content(screen, screen == currentScreen)
            }
        }
    }
}

private val AppScreen.depth: Int
    get() = when (this) {
        AppScreen.MAIN -> 0
        AppScreen.THEME_SETTINGS -> 2
        else -> 1
    }
