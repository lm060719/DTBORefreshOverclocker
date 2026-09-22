package io.mo.dtbooverclocker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import io.mo.dtbooverclocker.core.ActivePanelDetector
import io.mo.dtbooverclocker.model.TimingCandidate

enum class PanelFilterScope(val label: String)
{
    ACTIVE("本机在用"),
    VENDOR("厂商面板"),
    ALL("全部面板"),
    OTHER("其他面板")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingCandidateSelector(
    candidates: List<TimingCandidate>,
    selectedCandidateId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    activePanelIdentifier: String? = null,
    activePanelDisplayName: String? = null,
    activePanelSource: String? = null,
    selectionLabel: String = "选择待超频的原始时序档位：",
    selectFallback: Boolean = true
) {
    val groups = remember(candidates) {
        TimingUtils.groupCandidates(candidates)
    }

    if (groups.isEmpty()) {
        Text("未识别到可调整的 DSI 时序候选", style = MaterialTheme.typography.bodyMedium)
        return
    }

    val vendorCount = remember(groups) {
        groups.keys.count { it.classification == PanelClassification.VENDOR }
    }
    val activeCount = remember(groups, activePanelIdentifier) {
        if (activePanelIdentifier == null) 0
        else groups.keys.count { ActivePanelDetector.matchPanel(it.panelIdentifier, activePanelIdentifier) }
    }
    val hasVendorPanels = vendorCount > 0

    var filterScope by remember(hasVendorPanels, activeCount) {
        mutableStateOf(
            when
            {
                activeCount > 0 -> PanelFilterScope.ACTIVE
                hasVendorPanels -> PanelFilterScope.VENDOR
                else -> PanelFilterScope.ALL
            }
        )
    }
    var searchQuery by remember { mutableStateOf("") }

    val filteredGroups = remember(groups, filterScope, searchQuery, activePanelIdentifier) {
        val baseFiltered = groups.filter { (key, list) ->
            val scopeMatch = when (filterScope)
            {
                PanelFilterScope.ACTIVE ->
                    activePanelIdentifier != null &&
                        ActivePanelDetector.matchPanel(key.panelIdentifier, activePanelIdentifier)
                PanelFilterScope.VENDOR -> key.classification == PanelClassification.VENDOR
                PanelFilterScope.OTHER -> key.classification != PanelClassification.VENDOR
                PanelFilterScope.ALL -> true
            }
            val queryMatch = searchQuery.isBlank() ||
                key.panelDisplayName.contains(searchQuery, ignoreCase = true) ||
                key.panelIdentifier.contains(searchQuery, ignoreCase = true) ||
                list.any { cand ->
                    cand.currentHz.toString().contains(searchQuery) ||
                        cand.nodePath.contains(searchQuery, ignoreCase = true)
                }
            scopeMatch && queryMatch
        }

        if (activePanelIdentifier != null) {
            baseFiltered.toList().sortedByDescending { (key, _) ->
                ActivePanelDetector.matchPanel(key.panelIdentifier, activePanelIdentifier)
            }.toMap()
        } else {
            baseFiltered
        }
    }

    // 默认选中的分组（若已有选中的 candidate，则对应其所在分组；否则选在用面板或首个分组）
    val initialKey = remember(candidates, selectedCandidateId, filteredGroups, activePanelIdentifier) {
        val found = candidates.firstOrNull { it.id == selectedCandidateId }
        if (found != null) {
            filteredGroups.keys.firstOrNull {
                it.panelIdentifier == TimingUtils.parsePanelIdentifier(found.nodePath)
            } ?: filteredGroups.keys.firstOrNull()
                ?: groups.keys.first()
        } else if (activePanelIdentifier != null) {
            filteredGroups.keys.firstOrNull { ActivePanelDetector.matchPanel(it.panelIdentifier, activePanelIdentifier) }
                ?: filteredGroups.keys.firstOrNull()
                ?: groups.keys.first()
        } else {
            filteredGroups.keys.firstOrNull() ?: groups.keys.first()
        }
    }

    var activeGroupKey by remember(groups.keys, initialKey) {
        mutableStateOf(initialKey)
    }

    // 确保 activeGroupKey 始终有效
    if (activeGroupKey !in filteredGroups.keys) {
        activeGroupKey = filteredGroups.keys.firstOrNull() ?: groups.keys.first()
    }

    // 同一唯一面板可能重复存在于多个 DTB entry。面板只计数一次，但编辑必须保留精确 entry。
    val allGroupCandidates = filteredGroups[activeGroupKey].orEmpty()
    val entryIndices = remember(allGroupCandidates) {
        allGroupCandidates.map { it.entryIndex }.distinct().sorted()
    }
    val selectedInGroup = allGroupCandidates.firstOrNull { it.id == selectedCandidateId }
    var activeEntryIndex by remember(activeGroupKey, entryIndices) {
        mutableStateOf(selectedInGroup?.entryIndex ?: entryIndices.firstOrNull())
    }
    if (activeEntryIndex !in entryIndices)
    {
        activeEntryIndex = entryIndices.firstOrNull()
    }
    val currentGroupCandidates = remember(allGroupCandidates, activeEntryIndex) {
        activeEntryIndex?.let { entry ->
            allGroupCandidates.filter { it.entryIndex == entry }
        }.orEmpty()
    }
    val activeCandidate = currentGroupCandidates.firstOrNull { it.id == selectedCandidateId }
        ?: currentGroupCandidates.firstOrNull().takeIf { selectFallback }

    var showRawDetails by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 推荐在用屏幕提示条
        if (activePanelDisplayName != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            "本机正在使用的屏幕：$activePanelDisplayName",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (activePanelSource != null) {
                            Text(
                                "检测来源：$activePanelSource · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        // 面板选择区（若存在多个屏幕/DTB 分组时展示切换与过滤）
        if (groups.size > 1) {
            // 过滤维度按唯一 panel identifier 统计，不再把多个 DTB entry 的重复实例重复计数。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeCount > 0)
                {
                    FilterChip(
                        selected = filterScope == PanelFilterScope.ACTIVE,
                        onClick = { filterScope = PanelFilterScope.ACTIVE },
                        label = { Text("本机在用 ($activeCount)") },
                        leadingIcon = {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                if (hasVendorPanels)
                {
                    FilterChip(
                        selected = filterScope == PanelFilterScope.VENDOR,
                        onClick = { filterScope = PanelFilterScope.VENDOR },
                        label = { Text("厂商面板 ($vendorCount)") },
                        leadingIcon = {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }

                FilterChip(
                    selected = filterScope == PanelFilterScope.ALL,
                    onClick = { filterScope = PanelFilterScope.ALL },
                    label = { Text("全部唯一面板 (${groups.size})") }
                )

                FilterChip(
                    selected = filterScope == PanelFilterScope.OTHER,
                    onClick = { filterScope = PanelFilterScope.OTHER },
                    label = { Text("其他 (${groups.size - vendorCount})") }
                )
            }
            // 搜索框（支持搜索 o1, 38, 42, 144 等）
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索屏幕或时序", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 过滤后的面板切换芯片
            if (filteredGroups.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredGroups.forEach { (key, groupCandidates) ->
                        val isGroupActive = key == activeGroupKey
                        val isDetectedActive = activePanelIdentifier != null &&
                            ActivePanelDetector.matchPanel(key.panelIdentifier, activePanelIdentifier)
                        FilterChip(
                            selected = isGroupActive,
                            onClick = {
                                activeGroupKey = key
                                // 切换面板时自动将选中项设为该面板首个候选
                                groupCandidates.firstOrNull()?.let { onSelect(it.id) }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isDetectedActive) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "本机在用",
                                            modifier = Modifier.size(15.dp),
                                            tint = Color(0xFF2E7D32)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    } else if (key.classification == PanelClassification.VENDOR) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = "厂商面板",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (isGroupActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    val prefix = if (isDetectedActive) "在用·" else ""
                                    Text("${key.panelDisplayName} ($prefix${groupCandidates.size}档)")
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (isDetectedActive)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            } else {
                Text(
                    "未搜索到匹配的面板或时序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        } else {
            // 单面板时展示面板名称卡片
            val singleKey = groups.keys.first()
            val sample = currentGroupCandidates.firstOrNull()
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            singleKey.panelDisplayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${allGroupCandidates.map { it.entryIndex }.distinct().size} 个 DTB 实例",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (sample?.hActive != null && sample.vActive != null) {
                                Text("·", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${sample.hActive} × ${sample.vActive} 像素",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("·", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "当前 DTB ${currentGroupCandidates.size} 个档位 · 共 ${allGroupCandidates.size} 个实例",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (entryIndices.size > 1)
        {
            Text(
                "选择 DTB 实例：",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entryIndices.forEach { entryIndex ->
                    val count = allGroupCandidates.count { it.entryIndex == entryIndex }
                    FilterChip(
                        selected = activeEntryIndex == entryIndex,
                        onClick = {
                            activeEntryIndex = entryIndex
                            allGroupCandidates.firstOrNull { it.entryIndex == entryIndex }?.let { onSelect(it.id) }
                        },
                        label = { Text("DTB[$entryIndex] · $count 档") }
                    )
                }
            }
            Text(
                "同一唯一面板在多个 DTB entry 中重复出现；切换这里不会把它们重复计算成多块屏幕。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 刷新率档位卡片列表
        Text(
            selectionLabel,            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )

        if (currentGroupCandidates.isEmpty()) {
            Text("没有符合筛选条件的时序档位", style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            currentGroupCandidates.forEach { candidate ->
                val isSelected = candidate.id == (activeCandidate?.id ?: selectedCandidateId)
                TimingCandidateCard(
                    candidate = candidate,
                    selected = isSelected,
                    onClick = { onSelect(candidate.id) }
                )
            }
        }

        // 选定时序的技术详情折叠区（解决 raw 路径过长遮蔽视线的问题）
        activeCandidate?.let { cand ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRawDetails = !showRawDetails },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SettingsEthernet,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "底层设备树 (DTS) 节点详情",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(
                            if (showRawDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }

                    AnimatedVisibility(
                        visible = showRawDetails,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "节点路径：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        cand.nodePath,
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(cand.nodePath))
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "复制路径",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "DTS 范围: [${cand.nodeStart}..${cand.nodeEndExclusive}]",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "来源文件: ${cand.dtsFile.name}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingCandidateCard(
    candidate: TimingCandidate,
    selected: Boolean,
    onClick: () -> Unit
) {
    val nodeName = TimingUtils.parseTimingNodeName(candidate.nodePath)
    val clockStr = TimingUtils.formatClockCompact(candidate.pixelClockHz)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选中指示
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )

            Spacer(Modifier.width(12.dp))

            // 主标题：刷新率
            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${candidate.currentHz} Hz",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "DTB[${candidate.entryIndex}] · $nodeName",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    if (candidate.hasVendorDynamicMode) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "自动变频 / idle",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (candidate.hasOpaquePanelTimings) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "PHY Blob",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (candidate.hasVendorDynamicMode) {
                    Text(
                        "不建议修改或作为新增模板，请选择同面板的 normal 普通档位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(3.dp))

                // 副信息：时钟与分辨率
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Clock: $clockStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (candidate.hActive != null && candidate.vActive != null) {
                        Text(
                            "${candidate.hActive}×${candidate.vActive}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        if (candidate.hasFullGeometry) "时序完整" else "时序缺省",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (candidate.hasFullGeometry) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }
        }
    }
}
