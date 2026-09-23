package com.jaco.musicenhance.player

import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Establish immersive mode once. App-specific hooks prevent native code from undoing it. */
internal class PlayerSystemBars(private val window: Window) {
    private data class OriginalBars(
        val behavior: Int,
        val statusVisible: Boolean?,
        val navigationVisible: Boolean?,
    )

    private val barTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
    private var active = false
    private var controller: WindowInsetsController? = null
    private var original: OriginalBars? = null

    fun start() {
        if (active) return
        val insetsController = window.insetsController ?: return
        val insets = window.decorView.rootWindowInsets
        original = OriginalBars(
            insetsController.systemBarsBehavior,
            insets?.isVisible(WindowInsets.Type.statusBars()),
            insets?.isVisible(WindowInsets.Type.navigationBars()),
        )
        controller = insetsController
        active = true
        insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(barTypes)
    }

    fun stop(restoreVisibility: Boolean = true) {
        active = false
        val insetsController = controller ?: return
        controller = null
        original?.let { saved ->
            insetsController.systemBarsBehavior = saved.behavior
            fun restore(type: Int, visible: Boolean?) {
                when (visible) {
                    true -> insetsController.show(type)
                    false -> insetsController.hide(type)
                    null -> Unit // No initial insets available; don't invent the host's visibility.
                }
            }
            if (restoreVisibility) {
                restore(WindowInsets.Type.statusBars(), saved.statusVisible)
                restore(WindowInsets.Type.navigationBars(), saved.navigationVisible)
            }
        }
        original = null
    }
}
