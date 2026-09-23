package com.jaco.musicenhance.player

import android.graphics.Bitmap
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.LyricsSnapshot
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
    fun seekAndPlay(positionMs: Long)

    /** Optional app-specific features return cached data; the default provider is unsupported. */
    fun lyrics(snapshot: PlayerSnapshot): LyricsSnapshot = LyricsProvider.Unsupported.snapshot(snapshot)
    /** Remove per-view listeners and invalidate pending app-specific work. */
    fun release() {}
    fun controlState(): PlayerControlState
    fun cycleRepeat(): Boolean
    fun toggleFavorite(): Boolean
    fun nativeArtwork(): Bitmap?
    /** Whether a separate song-verified source can improve metadata/native artwork. */
    val hasArtworkProvider: Boolean get() = false
    /** Return artwork verified for this snapshot's song; null while unavailable/loading. */
    fun verifiedArtwork(snapshot: PlayerSnapshot): Bitmap? = null
    fun bassLevel(): Float
    fun setSpectrumPlaybackActive(active: Boolean)
}

internal const val PLAYER_OVERLAY_TAG = "musicenhance_cover_player"
