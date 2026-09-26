package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing

/** 语义色调，卡片、标签、提示条共用。 */
enum class Tone { Neutral, Primary, Success, Warning, Danger }

private data class ToneColors(val accent: Color, val container: Color, val onContainer: Color)

@Composable
private fun Tone.colors(): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val status = AppTheme.status
    return when (this) {
        Tone.Neutral -> ToneColors(scheme.onSurfaceVariant, scheme.surfaceContainerHigh, scheme.onSurface)
        Tone.Primary -> ToneColors(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer)
        Tone.Success -> ToneColors(status.success, status.successContainer, status.onSuccessContainer)
        Tone.Warning -> ToneColors(status.warning, status.warningContainer, status.onWarningContainer)
        Tone.Danger -> ToneColors(scheme.error, scheme.errorContainer, scheme.onErrorContainer)
    }
}

/**
 * 统一的内容卡片：可选图标 + 标题 + 副标题 + 右侧操作，下面是内容。
 * [tone] 为 Neutral 时使用普通 surface 容器，其他色调使用对应的 container 色。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Neutral,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = tone.colors()
    val container = if (tone == Tone.Neutral) MaterialTheme.colorScheme.surfaceContainerLow else colors.container.copy(alpha = 0.55f)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (title != null) {
                SectionHeader(title, subtitle, icon, if (tone == Tone.Neutral) MaterialTheme.colorScheme.primary else colors.accent, trailing)
            }
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconTint: Color,
    trailing: (@Composable RowScope.() -> Unit)?
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke(this)
    }
}

/** 只读状态标签，替代 onClick 为空的 AssistChip。 */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, icon: ImageVector? = null) {
    val colors = tone.colors()
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = colors.container,
        contentColor = colors.onContainer
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 带色条的提示横幅：信息、警告、错误。 */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Primary, icon: ImageVector? = null) {
    val colors = tone.colors()
    val resolvedIcon = icon ?: when (tone) {
        Tone.Danger -> Icons.Default.ErrorOutline
        Tone.Warning -> Icons.Default.Warning
        Tone.Success -> Icons.Default.CheckCircle
        else -> Icons.Default.Info
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.container.copy(alpha = 0.7f),
        contentColor = colors.onContainer
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(resolvedIcon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 次要说明文字。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** 标签 + 值 的一行，值可选等宽字体。 */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

/** 会自动换行的按钮组，避免窄屏或大字体时按钮被挤压截断。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content
    )
}

/** 按钮内的 图标 + 文字。 */
@Composable
fun IconLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text)
}

/** 危险操作按钮配色。 */
@Composable
fun dangerButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError
)

/** 列表式入口：左侧图标块 + 标题 / 副标题 + 右箭头。 */
@Composable
fun NavigationEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Primary
) {
    val colors = tone.colors()
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = colors.container, contentColor = colors.onContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 空状态占位。 */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    SectionCard(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            HintText(message)
            action?.invoke()
        }
    }
}
