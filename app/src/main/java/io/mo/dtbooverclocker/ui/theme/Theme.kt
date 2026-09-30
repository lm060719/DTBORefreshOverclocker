package io.mo.dtbooverclocker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme
import io.mo.dtbooverclocker.model.UiStyle

/** 全局间距刻度，页面与组件只使用这些值。 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    /** 页面左右留白。 */
    val page: Dp
        @Composable @ReadOnlyComposable get() = if (AppTheme.isMiuix) 12.dp else 16.dp

    /** 卡片内边距。 */
    val card: Dp
        @Composable @ReadOnlyComposable get() = 16.dp

    /** 内容卡片之间的间距，与 Miuix 设置列表的分组保持一致。 */
    val section: Dp
        @Composable @ReadOnlyComposable get() = if (AppTheme.isMiuix) 12.dp else 16.dp

    val title: Dp
        @Composable @ReadOnlyComposable get() = if (AppTheme.isMiuix) 26.dp else page
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val MaterialTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    )
}

private val AppTypography = defaultTextStyles().let { text ->
    Typography(
        displayLarge = text.title1,
        displayMedium = text.title1,
        displaySmall = text.title1,
        headlineLarge = text.title1,
        headlineMedium = text.title2,
        headlineSmall = text.title3,
        titleLarge = text.title4.copy(fontWeight = FontWeight.Medium),
        titleMedium = text.headline1.copy(fontWeight = FontWeight.Medium),
        titleSmall = text.headline2.copy(fontWeight = FontWeight.Medium),
        bodyLarge = text.paragraph,
        bodyMedium = text.body1,
        bodySmall = text.body2,
        labelLarge = text.button,
        labelMedium = text.footnote1,
        labelSmall = text.footnote2
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
private val LocalUiStyle = staticCompositionLocalOf { UiStyle.MATERIAL }

/** 访问扩展语义色：`AppTheme.status.success`。 */
object AppTheme {
    val isMiuix: Boolean
        @Composable @ReadOnlyComposable get() = LocalUiStyle.current == UiStyle.MIUIX

    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun AppTheme(
    uiStyle: UiStyle = UiStyle.MATERIAL,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val miuix = uiStyle == UiStyle.MIUIX
    val colors = if (dark) miuixDarkColorScheme() else miuixLightColorScheme()
    val scheme = when {
        miuix -> colors.materialScheme(dark)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(
        LocalUiStyle provides uiStyle,
        LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = if (miuix) AppTypography else MaterialTypography,
            shapes = if (miuix) AppShapes else MaterialShapes
        ) {
            val materialIndication = LocalIndication.current
            val materialOverscroll = LocalOverscrollFactory.current
            MiuixTheme(colors = colors) {
                CompositionLocalProvider(
                    LocalContentColor provides scheme.onBackground,
                    LocalIndication provides if (miuix) LocalIndication.current else materialIndication,
                    LocalOverscrollFactory provides if (miuix) LocalOverscrollFactory.current else materialOverscroll,
                    content = content
                )
            }
        }
    }
}

/** Keep the editor's Material inputs and dialogs in the same palette as Miuix. */
private fun Colors.materialScheme(dark: Boolean) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = tertiaryContainer,
        onPrimaryContainer = onTertiaryContainer,
        secondary = onSurfaceSecondary,
        onSecondary = background,
        secondaryContainer = secondaryVariant,
        onSecondaryContainer = onSecondaryVariant,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surfaceContainer,
        onSurface = onSurface,
        surfaceVariant = secondaryVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceTint = Color.Transparent,
        surfaceDim = surface,
        surfaceBright = surfaceContainer,
        surfaceContainerLowest = background,
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = dividerLine,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        inversePrimary = primary
    )
