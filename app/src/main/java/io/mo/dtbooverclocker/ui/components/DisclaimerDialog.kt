package io.mo.dtbooverclocker.ui.components

import androidx.activity.compose.BackHandler
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

@Composable
fun DisclaimerDialog(
    isFirstLaunch: Boolean = true,
    onConfirm: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit = onExit
) {
    var seconds by remember { mutableIntStateOf(if (isFirstLaunch) 5 else 0) }
    var isChecked by remember { mutableStateOf(false) }

    if (isFirstLaunch) {
        BackHandler(onBack = onExit)
        LaunchedEffect(Unit) {
            while (seconds > 0) {
                delay(1000)
                seconds--
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isFirstLaunch) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isFirstLaunch,
            dismissOnClickOutside = !isFirstLaunch
        ),
        icon = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = "风险提示与使用须知",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Risk & Disclaimer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // 欢迎语
                Text(
                    text = "欢迎使用 DTBO Refresh Overclocker。在继续使用并授予 Root 权限前，请务必仔细阅读以下内容：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
                )

                // 1. 高危操作声明
                DisclaimerSection(
                    icon = Icons.Default.Dangerous,
                    title = "1. 高危操作声明",
                    color = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    content = "本工具属于 Android 底层硬件调试与调校工具。使用本软件将会请求 Root 超级用户权限，并直接对设备的底层物理分区（dtbo）执行解包、修改并重写内核设备树（Device Tree Blob）操作。"
                )

                // 2. 潜在严重风险
                DisclaimerSection(
                    icon = Icons.Default.Warning,
                    title = "2. 潜在严重风险",
                    color = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    content = "屏幕刷新率超频受限于您的屏幕面板品质与显示驱动 IC（DDIC）硬件体质。任何不当的时序或频率参数可能导致：\n\n" +
                            "• 屏幕黑屏 / 花屏：开机后屏幕无法点亮或严重偏色、残影；\n" +
                            "• 系统无法启动（Bootloop）：内核加载异常导致卡开机 LOGO 或反复重启；\n" +
                            "• 硬件潜在损耗：长期超出标称频率运行可能导致发热加剧、器件加速老化或不可逆的物理损坏。"
                )

                // 3. 使用前提条件
                DisclaimerSection(
                    icon = Icons.Default.Shield,
                    title = "3. 使用前提条件",
                    color = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    content = "若要使用本软件，您必须拥有救砖的能力。"
                )

                // 4. 免责条款
                DisclaimerSection(
                    icon = Icons.Default.Info,
                    title = "4. 免责条款",
                    color = MaterialTheme.colorScheme.secondary,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    content = "本软件仅供设备所有者用于个人学习、显示技术研究与性能测试。开发者已尽可能提供单槽保护与校验机制，但无法担保本软件在所有设备、内核及系统版本下的兼容性与安全性。因使用本软件导致的任何设备损坏、数据丢失、保修失效或硬件故障，均由使用者自行承担全部责任。"
                )

                if (isFirstLaunch) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()

                    // 勾选确认行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { isChecked = !isChecked }
                            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { isChecked = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "我已完整阅读并充分理解上述风险，确认具备独立救砖能力并自愿承担全部后果。",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isFirstLaunch) {
                val isButtonEnabled = isChecked && seconds == 0
                Button(
                    onClick = onConfirm,
                    enabled = isButtonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(
                        if (seconds > 0) "同意并继续 (${seconds}s)" else "同意并继续"
                    )
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("我知道了")
                }
            }
        },
        dismissButton = {
            if (isFirstLaunch) {
                OutlinedButton(onClick = onExit) {
                    Text("退出应用")
                }
            }
        }
    )
}

@Composable
private fun DisclaimerSection(
    icon: ImageVector,
    title: String,
    content: String,
    color: Color,
    containerColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.25
            )
        }
    }
}
