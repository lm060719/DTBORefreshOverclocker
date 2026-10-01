package io.mo.dtbooverclocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.mo.dtbooverclocker.model.UiStyle
import io.mo.dtbooverclocker.ui.components.AppCard
import io.mo.dtbooverclocker.ui.components.BottomBarPreferences
import io.mo.dtbooverclocker.ui.components.IconButton
import io.mo.dtbooverclocker.ui.components.MonetPreference
import io.mo.dtbooverclocker.ui.components.MonetPreferenceRow
import io.mo.dtbooverclocker.ui.components.PredictiveBackPreference
import io.mo.dtbooverclocker.ui.components.Scaffold
import io.mo.dtbooverclocker.ui.components.TopAppBar
import io.mo.dtbooverclocker.ui.components.UiStylePreference
import io.mo.dtbooverclocker.ui.components.UiStyleSelector
import io.mo.dtbooverclocker.ui.components.appBarScroll
import io.mo.dtbooverclocker.ui.i18n.I18n
import io.mo.dtbooverclocker.ui.theme.AppTheme
import io.mo.dtbooverclocker.ui.theme.Spacing
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior

@Composable
internal fun ThemeSettingsScreen(
    state: MainUiState,
    onNavigateBack: () -> Unit,
    onSetUiStyle: (UiStyle) -> Unit,
    onSetMonetColors: (Boolean) -> Unit,
    onSetFloatingBottomBar: (Boolean) -> Unit,
    onSetLiquidGlass: (Boolean) -> Unit,
    onSetPredictiveBack: (Boolean) -> Unit,
    backHandlerEnabled: Boolean = true
) {
    BackHandler(enabled = backHandlerEnabled, onBack = onNavigateBack)
    val strings = I18n.current
    val miuix = AppTheme.isMiuix
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = Modifier.testTag("theme-settings-screen").appBarScroll(scrollBehavior),
        topBar = {
            TopAppBar(
                title = strings.settingsTheme,
                scrollBehavior = scrollBehavior,
                titlePadding = Spacing.title,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.backToSettings)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.page, end = Spacing.page, top = Spacing.sm, bottom = Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.section)
        ) {
            if (miuix) {
                item(key = "appearance") {
                    AppCard(Modifier.fillMaxWidth()) {
                        UiStylePreference(state.uiStyle, onSetUiStyle)
                        MonetPreferenceRow(state.monetColors, onSetMonetColors)
                    }
                }
            } else {
                item(key = "appearance") { UiStyleSelector(state.uiStyle, onSetUiStyle) }
                item(key = "monet") { MonetPreference(state.monetColors, onSetMonetColors) }
            }
            item(key = "bottom-bar") {
                BottomBarPreferences(state.floatingBottomBar, state.liquidGlass, onSetFloatingBottomBar, onSetLiquidGlass)
            }
            item(key = "predictive-back") {
                PredictiveBackPreference(state.predictiveBack, onSetPredictiveBack)
            }
        }
    }
}
