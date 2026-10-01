package io.mo.dtbooverclocker.ui.components

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme

/** 返回手势进度事件从 Android 14 起才会下发到应用内。 */
internal fun supportsPredictiveBack(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

@Composable
internal fun PredictiveBackPreference(checked: Boolean, onChange: (Boolean) -> Unit) {
    val strings = I18n.current
    val supported = supportsPredictiveBack()
    val row = @Composable {
        AppSwitchPreference(
            tag = "predictive-back",
            title = strings.predictiveBack,
            summary = if (supported) strings.predictiveBackDesc else strings.predictiveBackUnsupported,
            icon = Icons.Rounded.Swipe,
            checked = checked && supported,
            enabled = supported,
            onChange = onChange
        )
    }
    if (AppTheme.isMiuix) {
        AppCard(Modifier.fillMaxWidth()) { row() }
    } else {
        SectionCard(title = strings.settingsInteraction, icon = Icons.Rounded.Swipe) { row() }
    }
}
