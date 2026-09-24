package com.jaco.musicenhance.player

import android.graphics.Bitmap
import com.jaco.musicenhance.player.artwork.ArtworkProvider
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Fast reads only; providers schedule network, decoding and native IPC on their own workers. */
internal interface PlayerDataSource {
    fun snapshot(): PlayerSnapshot
    fun addListener(listener: (PlayerSnapshot) -> Unit)
    fun removeListener(listener: (PlayerSnapshot) -> Unit)
    fun controlState(): PlayerControlState = snapshot().controls
    fun nativeArtwork(): Bitmap? = null
    fun bassLevel(): Float
}

/** Optional actions report unsupported instead of fabricating a state change. */
internal data class PlayerActions(
    val playPause: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val seekTo: (Long) -> Unit,
    val seekAndPlay: (Long) -> Unit,
    val cycleRepeat: () -> Boolean = { false },
    val toggleFavorite: () -> Boolean = { false },
    val setSpectrumPlaybackActive: (Boolean) -> Unit = {},
)

/** One screen's data and operations; adapters supply this without owning any enhanced UI. */
internal data class PlayerSession(
    val appName: String,
    val data: PlayerDataSource,
    val actions: PlayerActions,
    val lyrics: LyricsProvider = LyricsProvider.Unsupported,
    val artwork: ArtworkProvider = ArtworkProvider.Unsupported,
    val onRelease: () -> Unit = {},
)
