package io.mo.dtbooverclocker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.Spacing
import io.mo.dtbooverclocker.ui.theme.AppTheme

@Composable
fun UiStyleSelector(selected: UiStyle, onSelect: (UiStyle) -> Unit) {
    if (AppTheme.isMiuix) {
        AppCard(Modifier.fillMaxWidth()) { UiStylePreference(selected, onSelect) }
        return
    }
    val strings = I18n.current
    SectionCard(
        title = strings.settingsAppearance,
        subtitle = strings.settingsAppearanceDesc,
        icon = Icons.Default.Palette
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            UiStyle.entries.forEach { style ->
                val checked = selected == style
                Surface(
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("ui-style-${style.code}")
                            .selectable(selected = checked, role = Role.RadioButton, onClick = { onSelect(style) })
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Text(style.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = checked, onClick = null)
                    }
                }
            }
        }
    }
}
