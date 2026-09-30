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
