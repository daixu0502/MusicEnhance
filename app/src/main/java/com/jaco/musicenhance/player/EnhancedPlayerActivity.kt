package com.jaco.musicenhance.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.graphics.drawable.toDrawable
import com.jaco.musicenhance.player.ui.CoverPlayerView
import java.lang.ref.WeakReference

/** Instantiated in the music app's process through its registered Activity slot. No host UI code runs here. */
internal class EnhancedPlayerActivity : Activity() {
    private var session: PlayerActivitySession? = null
    private var player: CoverPlayerView? = null
    private var layout: PlayerWindowLayout? = null
    private var pageTransition: PlayerPageTransition? = null
    private var closing = false
    private var started = false
    private var registeredBack = false
    private val back = object : OnBackAnimationCallback {
        // Owning the predictive phase keeps this opaque Activity in place instead of allowing
        // the system gesture animation to reveal the native player underneath. The common close
        // path still runs only after the gesture is committed; cancellation needs no rollback.
        override fun onBackInvoked() = dismissPlayer()
    }

    // This cover-only Activity supports 0°/180° so the camera rail never rotates onto the controls.
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        // Module animation resources cannot be resolved by the borrowed host package's window.
        // Move our own content instead, on an opaque backdrop that protects the native page.
        window.setBackgroundDrawable(Color.BLACK.toDrawable())
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
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
            pageTransition = PlayerPageTransition(it).also { transition ->
                if (savedInstanceState == null) transition.enter()
            }
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back)
        registeredBack = true
    }

    override fun onStart() {
        super.onStart()
        started = true
    }

    override fun onResume() {
        super.onResume()
        if (session?.canShow(this) != true) dismissPlayer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (session?.canShow(this) != true) dismissPlayer() else player?.refreshDisplayLayout()
    }

    @SuppressLint("GestureBackNavigation") // Host manifest opt-out still requires legacy dispatch.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // The borrowed host component may opt out of OnBackInvokedCallback. Its gesture then
        // arrives here as legacy back; Activity's default would finish before native dismissal.
        dismissPlayer()
    }

    private fun dismissPlayer() {
        if (closing || isFinishing) return
        // Close/collapse the native data page first, so returning never exposes its player.
        if (!runCatching { session?.dismiss() ?: true }.getOrDefault(false)) return
        finish()
    }

    override fun finish() {
        if (closing || isFinishing) return
        closing = true
        player?.prepareForDismissal()
        val transition = pageTransition
        if (transition == null || !started) super.finish() else transition.exit { super.finish() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        closing || super.dispatchTouchEvent(event)

    override fun onStop() {
        started = false
        pageTransition?.completeExit()
        super.onStop()
    }

    override fun onDestroy() {
        pageTransition?.release()
        pageTransition = null
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
