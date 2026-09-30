package io.mo.dtbooverclocker.ui.theme

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal fun supportsMonet(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Refresh after a system palette change or returning from the wallpaper picker. */
@Composable
internal fun rememberMonetRevision(enabled: Boolean): Int {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, owner, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("theme_customization_overlay_packages"), false, observer
        )
        owner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            owner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return revision
}
