package com.jaco.musicenhance.player

import android.app.Activity
import java.lang.ref.WeakReference

/** In-process handoff. Providers use the enhanced Activity; navigation recovery is independent. */
internal class PlayerActivitySession(
    val windowTitle: String,
    val createController: (Activity) -> PlayerController?,
    val canShow: (Activity) -> Boolean,
    val keepScreenOn: () -> Boolean,
    val onDismiss: () -> Boolean,
    val onNavigationReleased: () -> Unit,
    val onLaunchFailed: () -> Unit = {},
    val prepareBackground: (() -> Boolean)? = null,
) {
    var activity = WeakReference<EnhancedPlayerActivity>(null)
    private var dismissed = false
    private var closed = false
    private var navigationReleased = false
    private var launchFailed = false

    fun failLaunch() {
        if (closed || navigationReleased || launchFailed) return
        launchFailed = true
        // The fallback owns the native page now; failed Activity destruction must not collapse it.
        dismissed = true
        releaseNavigation()
        onLaunchFailed()
    }

    fun dismiss(): Boolean {
        if (dismissed) return true
        dismissed = onDismiss()
        return dismissed
    }

    fun close() {
        if (closed) return
        closed = true
        activity.clear()
        releaseNavigation()
    }

    /** A finishing transparent Activity can outlive the home becoming interactive. */
    fun releaseNavigation() {
        if (navigationReleased) return
        navigationReleased = true
        onNavigationReleased()
    }
}

/** Tokens survive Activity recreation, but never attempt to restore native objects after process death. */
internal object PlayerActivitySessions {
    private val sessions = mutableMapOf<String, PlayerActivitySession>()
    const val ACTION = "com.jaco.musicenhance.action.ENHANCED_PLAYER"
    const val EXTRA_SESSION = "com.jaco.musicenhance.PLAYER_SESSION"
    const val OWNED_ACTIVITY_METADATA = "com.jaco.musicenhance.ENHANCED_PLAYER"

    fun put(token: String, session: PlayerActivitySession) { sessions[token] = session }
    fun find(token: String?) = sessions[token]
    fun remove(token: String) { sessions.remove(token)?.close() }
}
