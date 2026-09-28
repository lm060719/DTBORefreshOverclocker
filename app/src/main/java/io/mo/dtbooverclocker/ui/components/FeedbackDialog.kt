package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.feedback.FeedbackDeviceInfo
import io.mo.dtbooverclocker.feedback.FeedbackType
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing

/**
 * 反馈向导：选择类型 → 保存反馈包（原始 DTBO + 日志 + 设备信息）→ 打开预填好的 GitHub Issue。
 * GitHub 无法通过链接附带文件，反馈包需由用户拖进 Issue。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackDialog(
    type: FeedbackType,
    info: FeedbackDeviceInfo?,
    imageName: String?,
    rootGranted: Boolean,
    bundleSaved: Boolean,
    onTypeChange: (FeedbackType) -> Unit,
    onSaveBundle: () -> Unit,
    onOpenIssue: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = I18n.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Feedback, contentDescription = null) },
        title = { Text(strings.feedbackTitle) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FeedbackType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { onTypeChange(option) },
                            label = {
                                Text(when (option) {
                                    FeedbackType.BUG -> strings.feedbackTypeBug
                                    FeedbackType.PANEL_RECOMMEND -> strings.feedbackTypePanel
                                    FeedbackType.FEATURE -> strings.feedbackTypeFeature
                                })
                            }
                        )
                    }
                }

                Text(strings.feedbackAutoFilled, style = MaterialTheme.typography.labelLarge)
                if (info == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(strings.feedbackLoading, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    InfoLine(strings.feedbackDevice, info.device)
                    if (type != FeedbackType.FEATURE) {
                        InfoLine(strings.feedbackRom, info.rom)
                        InfoLine(strings.feedbackAndroid, info.android)
                        InfoLine(strings.feedbackAppVersion, info.appVersion)
                    }
                    if (type == FeedbackType.PANEL_RECOMMEND) {
                        InfoLine(strings.feedbackRecommendedPanel, info.recommendedPanel ?: strings.feedbackNone)
                    }
                }

                if (type.needsBundle) {
                    Text(strings.feedbackBundleContents, style = MaterialTheme.typography.labelLarge)
                    if (imageName != null) {
                        BundleLine(strings.feedbackBundleImage(imageName))
                    } else {
                        NoticeBanner(strings.feedbackBundleNoImage, tone = Tone.Warning)
                    }
                    BundleLine(strings.feedbackBundleLogs)
                    if (type == FeedbackType.PANEL_RECOMMEND && !rootGranted) {
                        NoticeBanner(strings.feedbackPanelNeedsRoot, tone = Tone.Warning)
                    }
                    OutlinedButton(onClick = onSaveBundle, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (bundleSaved) Icons.Default.CheckCircle else Icons.Default.SaveAlt,
                            contentDescription = null,
                            tint = if (bundleSaved) AppTheme.status.success else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (bundleSaved) strings.feedbackBundleSaved else strings.feedbackSaveBundle)
                    }
                    Text(strings.feedbackUploadHint, style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    strings.feedbackPrivacy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenIssue, enabled = info != null) {
                Text(strings.feedbackOpenGithub)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun BundleLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppTheme.status.success, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
