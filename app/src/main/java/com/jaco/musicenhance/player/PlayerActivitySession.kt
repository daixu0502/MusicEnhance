package com.jaco.musicenhance.player

import android.app.Activity
import java.lang.ref.WeakReference

/** In-process handoff. Native roots/controllers are created by the adapter, never by the Activity. */
internal class PlayerActivitySession(
    val windowTitle: String,
    val createController: () -> PlayerController?,
    val canShow: (Activity) -> Boolean,
    val keepScreenOn: () -> Boolean,
    val onDismiss: () -> Boolean,
    val onClosed: () -> Unit,
    val onLaunchFailed: () -> Unit = {},
) {
    var activity = WeakReference<EnhancedPlayerActivity>(null)
    private var dismissed = false
    private var closed = false
    private var launchFailed = false

    fun failLaunch() {
        if (closed || launchFailed) return
        launchFailed = true
        // The fallback owns the native page now; failed Activity destruction must not collapse it.
        dismissed = true
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
        onClosed()
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
