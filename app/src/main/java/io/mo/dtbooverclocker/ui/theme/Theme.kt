package io.mo.dtbooverclocker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 全局间距刻度，页面与组件只使用这些值。 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    /** 页面左右留白。 */
    val page = 16.dp

    /** 卡片内边距。 */
    val card = 16.dp
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    )
}

/** Material colorScheme 之外的语义色：成功 / 警告，危险直接使用 colorScheme.error。 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LightStatusColors = StatusColors(
    success = Color(0xFF2E7D32),
    onSuccess = Color.White,
    successContainer = Color(0xFFDDF3DE),
    onSuccessContainer = Color(0xFF0B3D10),
    warning = Color(0xFFB45309),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFEBD2),
    onWarningContainer = Color(0xFF4A2400)
)

private val DarkStatusColors = StatusColors(
    success = Color(0xFF81C784),
    onSuccess = Color(0xFF0B3D10),
    successContainer = Color(0xFF1E4620),
    onSuccessContainer = Color(0xFFC8EBC9),
    warning = Color(0xFFFFB74D),
    onWarning = Color(0xFF4A2400),
    warningContainer = Color(0xFF5C3A10),
    onWarningContainer = Color(0xFFFFDDB3)
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** 访问扩展语义色：`AppTheme.status.success`。 */
object AppTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
