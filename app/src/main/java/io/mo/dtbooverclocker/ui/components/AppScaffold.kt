package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/** Extra scrollable space keeps the final list item above the floating navigation capsule. */
internal val LocalFloatingBarInset = staticCompositionLocalOf { 0.dp }

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingToolbar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    // A stable host preserves page state and lets native Miuix preference popups cover the screen.
    top.yukonga.miuix.kmp.basic.Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        floatingToolbar = floatingToolbar,
        containerColor = MaterialTheme.colorScheme.background,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: String,
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    titlePadding: Dp = Spacing.title,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    if (AppTheme.isMiuix) {
        top.yukonga.miuix.kmp.basic.TopAppBar(
            title = title,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            titlePadding = titlePadding,
            navigationIcon = navigationIcon,
            actions = actions
        )
    } else {
        androidx.compose.material3.TopAppBar(
            title = {
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions = actions
        )
    }
}

@Composable
fun Modifier.appBarScroll(behavior: ScrollBehavior): Modifier =
    if (AppTheme.isMiuix) nestedScroll(behavior.nestedScrollConnection) else this
