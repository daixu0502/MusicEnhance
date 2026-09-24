package com.jaco.musicenhance.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.Window
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jaco.musicenhance.player.ui.CoverPlayerView
import java.lang.ref.WeakReference

/** Instantiated in the music app's process through its registered Activity slot. No host UI code runs here. */
internal class EnhancedPlayerActivity : Activity() {
    private var session: PlayerActivitySession? = null
    private var player: CoverPlayerView? = null
    private var layout: PlayerWindowLayout? = null
    private var registeredBack = false
    private val back = OnBackInvokedCallback { dismissPlayer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        val current = PlayerActivitySessions.find(intent?.getStringExtra(PlayerActivitySessions.EXTRA_SESSION))
        if (current == null) { finish(); return }
        session = current
        if (!current.canShow(this)) { current.failLaunch(); finish(); return }
        current.activity = WeakReference(this)
        val controller = runCatching(current.createController).onFailure {
            com.jaco.musicenhance.hook.moduleInfo("Enhanced player session unavailable: $it")
        }.getOrNull()
        if (controller == null) { current.failLaunch(); finish(); return }
        layout = PlayerWindowLayout(window, current.windowTitle).also { it.enter() }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        player = CoverPlayerView(
            this, window, controller, onDismiss = ::dismissPlayer,
            keepScreenOnRequested = { current.canShow(this) && current.keepScreenOn() },
        ).also {
            it.tag = PLAYER_OVERLAY_TAG
            setContentView(it)
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back)
        registeredBack = true
    }

    override fun onResume() {
        super.onResume()
        if (session?.canShow(this) != true) dismissPlayer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (session?.canShow(this) != true) dismissPlayer() else player?.refreshDisplayLayout()
    }

    private fun dismissPlayer() {
        if (isFinishing) return
        // Close/collapse the native data page first, so returning never exposes its player.
        if (!runCatching { session?.dismiss() ?: true }.getOrDefault(false)) return
        player?.prepareForDismissal()
        finish()
    }

    override fun onDestroy() {
        if (registeredBack) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        // Detaching the view removes listeners and releases all session providers.
        player?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        player = null
        layout?.restore()
        layout = null
        if (!isChangingConfigurations) {
            runCatching { session?.dismiss() }
            intent?.getStringExtra(PlayerActivitySessions.EXTRA_SESSION)?.let(PlayerActivitySessions::remove)
        }
        session = null
        super.onDestroy()
    }
}
