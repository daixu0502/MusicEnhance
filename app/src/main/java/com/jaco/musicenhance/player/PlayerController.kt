package com.jaco.musicenhance.player

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/**
 * Called on the UI thread. Return cached state and queue slow IPC/network work on a worker.
 * Native view reads and clicks stay on the UI thread. Unknown optional state must remain unknown.
 */
internal interface PlayerController {
    val appName: String
    fun snapshot(): PlayerSnapshot
    fun addListener(listener: (PlayerSnapshot) -> Unit)
    fun removeListener(listener: (PlayerSnapshot) -> Unit)
    fun playPause()
    fun previous()
    fun next()
    fun seekTo(positionMs: Long)
    fun controlState(): PlayerControlState
    fun cycleRepeat(): Boolean
    fun toggleFavorite(): Boolean
    fun nativeArtwork(): Bitmap?
    fun highResolutionArtwork(snapshot: PlayerSnapshot): Bitmap?
    fun bassLevel(): Float
    fun setSpectrumPlaybackActive(active: Boolean)
}

internal const val PLAYER_OVERLAY_TAG = "musicenhance_cover_player"
