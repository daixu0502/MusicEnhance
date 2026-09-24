package com.jaco.musicenhance.player

import android.view.Window
import android.view.WindowManager

/** Both WMS and the app's local frame calculation must allow drawing under the camera cutout. */
internal class PlayerWindowLayout(
    private val window: Window,
    private val playerTitle: String? = null,
) {
    private var originalTitle: CharSequence? = null
    private var originalCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    private var active = false

    fun enter() {
        if (active) return
        originalTitle = window.attributes.title
        originalCutoutMode = window.attributes.layoutInDisplayCutoutMode
        active = true
        window.attributes = window.attributes.apply {
            // The marker ties system policy to this owned window, including a borrowed host slot.
            playerTitle?.let { title = it }
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    fun restore() {
        if (!active) return
        active = false
        window.attributes = window.attributes.apply {
            // Don't overwrite a title the host changed while the player was displayed.
            if (playerTitle != null && title?.toString() == playerTitle) title = originalTitle
            layoutInDisplayCutoutMode = originalCutoutMode
        }
    }
}
