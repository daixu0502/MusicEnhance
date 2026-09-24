package com.jaco.musicenhance.player

import android.view.Window
import android.view.WindowManager

/** Both WMS and the app's local frame calculation must allow drawing under the camera cutout. */
internal class PlayerWindowLayout(
    private val window: Window,
    private val embeddedPlayerTitle: String? = null,
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
            // Dedicated Activities keep their component title. Only shared home/player windows
            // need a marker for the system hook; fullscreen alone doesn't bypass cutout clipping.
            embeddedPlayerTitle?.let { title = it }
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    fun restore() {
        if (!active) return
        active = false
        window.attributes = window.attributes.apply {
            // Don't overwrite a title the host changed while the player was displayed.
            if (embeddedPlayerTitle != null && title?.toString() == embeddedPlayerTitle) title = originalTitle
            layoutInDisplayCutoutMode = originalCutoutMode
        }
    }
}
