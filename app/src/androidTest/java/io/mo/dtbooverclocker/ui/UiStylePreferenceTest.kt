package io.mo.dtbooverclocker.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.mo.dtbooverclocker.model.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class UiStylePreferenceTest {
    @Test
    fun monetChoiceSurvivesRecreationIndependentlyOfStyle() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
        val previousMonet = if (prefs.contains("monet_colors")) prefs.getBoolean("monet_colors", true) else null
        val previousStyle = prefs.getString("ui_style", null)
        val stores = mutableListOf<ViewModelStore>()
        fun newViewModel(): MainViewModel {
            val store = ViewModelStore().also(stores::add)
            return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
        }
        try {
            prefs.edit().remove("monet_colors").commit()
            val initial = newViewModel()
            assertEquals(true, initial.state.value.monetColors)
            initial.setMonetColors(false)
            initial.setUiStyle(UiStyle.MIUIX)
            val restored = newViewModel()
            assertEquals(false, restored.state.value.monetColors)
            restored.setUiStyle(UiStyle.MATERIAL)
            assertEquals(false, newViewModel().state.value.monetColors)
            restored.setMonetColors(true)
            assertEquals(true, newViewModel().state.value.monetColors)
        } finally {
            stores.forEach { it.clear() }
            prefs.edit().apply {
                if (previousMonet == null) remove("monet_colors") else putBoolean("monet_colors", previousMonet)
                if (previousStyle == null) remove("ui_style") else putString("ui_style", previousStyle)
            }.commit()
        }
    }

    @Test
    fun bottomBarChoicesSurviveRecreationAndDisablingFloating() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
        val keys = listOf("floating_bottom_bar", "liquid_glass")
        val previous = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val stores = mutableListOf<ViewModelStore>()
        fun newViewModel(): MainViewModel {
            val store = ViewModelStore().also(stores::add)
            return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
        }
        try {
            prefs.edit().remove(keys[0]).remove(keys[1]).commit()
            val initial = newViewModel()
            assertEquals(false, initial.state.value.floatingBottomBar)
            assertEquals(false, initial.state.value.liquidGlass)
            initial.setFloatingBottomBar(true)
            initial.setLiquidGlass(true)
            val restored = newViewModel()
            assertEquals(true, restored.state.value.floatingBottomBar)
            assertEquals(true, restored.state.value.liquidGlass)
            restored.setFloatingBottomBar(false)
            val disabled = newViewModel()
            assertEquals(false, disabled.state.value.floatingBottomBar)
            assertEquals(true, disabled.state.value.liquidGlass)
        } finally {
            stores.forEach { it.clear() }
            prefs.edit().apply {
                previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
        }
    }

    @Test
    fun selectedStyleSurvivesViewModelRecreation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("dtbo_prefs", Context.MODE_PRIVATE)
        val previous = prefs.getString("ui_style", null)
        val stores = mutableListOf<ViewModelStore>()
        fun newViewModel(): MainViewModel {
            val store = ViewModelStore().also(stores::add)
            return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
        }
        try {
            newViewModel().setUiStyle(UiStyle.MIUIX)
            val restored = newViewModel()
            assertEquals(UiStyle.MIUIX, restored.state.value.uiStyle)
            restored.setUiStyle(UiStyle.MATERIAL)
            assertEquals(UiStyle.MATERIAL, newViewModel().state.value.uiStyle)
        } finally {
            stores.forEach { it.clear() }
            prefs.edit().apply {
                if (previous == null) remove("ui_style") else putString("ui_style", previous)
            }.commit()
        }
    }
}
