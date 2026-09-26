package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.background
import io.mo.dtbooverclocker.ui.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.TimingCandidate
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingGeometryChart(
    candidate: TimingCandidate,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DSI 时序几何剖面",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = if (candidate.hasFullGeometry) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = if (candidate.hasFullGeometry) "完整几何" else "部分缺省",
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (candidate.hasFullGeometry) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }

            if (!candidate.hasFullGeometry) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = Spacing.xs)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "该节点缺少完整的 Front/Back Porch 参数，部分超频策略将自动降级为纯时钟模式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 水平时序 (H-Timing)
                val hAct = candidate.hActive ?: 0
                val hFp = candidate.hFrontPorch ?: 0
                val hSync = candidate.hSync ?: 0
                val hBp = candidate.hBackPorch ?: 0
                val hTotal = hAct + hFp + hSync + hBp
                val hBlank = hFp + hSync + hBp
                val hBlankPct = if (hTotal > 0) (hBlank.toDouble() / hTotal) * 100.0 else 0.0

                TimingAxisBar(
                    axisName = "水平轴 (Horizontal)",
                    active = hAct,
                    frontPorch = hFp,
                    sync = hSync,
                    backPorch = hBp,
                    total = hTotal,
                    unit = "px",
                    blankingPct = hBlankPct
                )

                // 垂直时序 (V-Timing)
                val vAct = candidate.vActive ?: 0
                val vFp = candidate.vFrontPorch ?: 0
                val vSync = candidate.vSync ?: 0
                val vBp = candidate.vBackPorch ?: 0
                val vTotal = vAct + vFp + vSync + vBp
                val vBlank = vFp + vSync + vBp
                val vBlankPct = if (vTotal > 0) (vBlank.toDouble() / vTotal) * 100.0 else 0.0

                TimingAxisBar(
                    axisName = "垂直轴 (Vertical)",
                    active = vAct,
                    frontPorch = vFp,
                    sync = vSync,
                    backPorch = vBp,
                    total = vTotal,
                    unit = "行",
                    blankingPct = vBlankPct
                )

                // 图例说明
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    LegendItem(color = MaterialTheme.colorScheme.primary, label = "Active 显像区")
                    LegendItem(color = MaterialTheme.colorScheme.secondary, label = "Front Porch 前肩")
                    LegendItem(color = MaterialTheme.colorScheme.tertiary, label = "Sync 同步脉宽")
                    LegendItem(color = MaterialTheme.colorScheme.outline, label = "Back Porch 后肩")
                }
            }
        }
    }
}

@Composable
private fun TimingAxisBar(
    axisName: String,
    active: Int,
    frontPorch: Int,
    sync: Int,
    backPorch: Int,
    total: Int,
    unit: String,
    blankingPct: Double
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                axisName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "总计 $total $unit (消隐 ${String.format(Locale.US, "%.1f", blankingPct)}%)",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 分段比例条
        if (total > 0) {
            val weightActive = (active.toFloat() / total).coerceIn(0.01f, 1f)
            val weightFp = (frontPorch.toFloat() / total).coerceIn(0.01f, 1f)
            val weightSync = (sync.toFloat() / total).coerceIn(0.01f, 1f)
            val weightBp = (backPorch.toFloat() / total).coerceIn(0.01f, 1f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
            ) {
                Box(
                    modifier = Modifier
                        .weight(weightActive)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Box(
                    modifier = Modifier
                        .weight(weightFp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.secondary)
                )
                Box(
                    modifier = Modifier
                        .weight(weightSync)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Box(
                    modifier = Modifier
                        .weight(weightBp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }

        // 参数具体数值细览
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "显像 $active",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "前肩 $frontPorch",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                "同步 $sync",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                "后肩 $backPorch",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

