package com.jaco.musicenhance.player

import com.jaco.musicenhance.player.model.PlayerDisplayState

/**
 * Presentation boundary: emit resolved state on the main thread and receive user actions.
 * The view never reads native metadata, requests lyrics or selects between artwork sources.
 */
internal interface PlayerController {
    val appName: String
    fun addListener(listener: (PlayerDisplayState) -> Unit)
    fun removeListener(listener: (PlayerDisplayState) -> Unit)
    fun setActive(active: Boolean)
    fun setLyricsRequested(requested: Boolean)
    fun playPause()
    fun previous()
    fun next()
    fun seekTo(positionMs: Long, trackKey: String)
    fun seekAndPlay(positionMs: Long, trackKey: String)

    /** Stop updates, remove listeners and invalidate pending app-specific work. */
    fun release() {}
    fun cycleRepeat(): Boolean
    fun toggleFavorite(): Boolean
    /** Read the latest cached audio sample at the display's frame rate; no IPC or analysis here. */
    fun bassLevel(): Float
}

internal const val PLAYER_OVERLAY_TAG = "musicenhance_cover_player"
