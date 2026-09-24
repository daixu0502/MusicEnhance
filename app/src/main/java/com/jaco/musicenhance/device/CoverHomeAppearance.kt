package com.jaco.musicenhance.device

import android.app.Activity
import android.graphics.Color
import android.view.WindowInsets
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.hook.hookEnabled
import java.util.WeakHashMap

/** Restore native cover home chrome after leaving the full-screen player. */
internal object CoverHomeAppearance {
    private data class Bars(val color: Int, val contrast: Boolean, val navigationVisible: Boolean)
    private val originalBars = WeakHashMap<Activity, Bars>()

    fun update(activity: Activity) {
        val profile = MusicAppRegistry.find(activity.packageName) ?: return
        if (!profile.hideCoverHomeNavigationBar) return
        if (!profile.isHomeActivity(activity.javaClass.name)) return
        val window = activity.window
        @Suppress("DEPRECATION")
        if (hookEnabled(profile) && CoverScreenDetector.isCoverScreen(activity)) {
            originalBars.getOrPut(activity) {
                Bars(window.navigationBarColor, window.isNavigationBarContrastEnforced,
                    window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.navigationBars()) ?: true)
            }
            // HyperOS' scaled app window retains a navigationBarBackground although the cover
            // has no navigation bar. Release only this inset, keeping the native half-screen layout.
            window.isNavigationBarContrastEnforced = false
            window.navigationBarColor = Color.TRANSPARENT
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
        } else {
            originalBars.remove(activity)?.let {
                window.navigationBarColor = it.color
                window.isNavigationBarContrastEnforced = it.contrast
                if (it.navigationVisible) window.insetsController?.show(WindowInsets.Type.navigationBars())
            }
        }
    }

    fun onDestroyed(activity: Activity) {
        originalBars.remove(activity)
    }
}
