package com.jaco.musicenhance.player.model

import android.graphics.Bitmap

/** Ready-to-render data. Source selection, song matching and loading stay outside the view. */
internal data class PlayerDisplayState(
    val trackKey: String = "",
    val title: String = PlayerSnapshot.Empty.title,
    val artist: String = PlayerSnapshot.Empty.artist,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val artwork: Bitmap? = null,
    val thumbnail: Bitmap? = null,
    val controls: PlayerControlState = PlayerControlState(),
    val lyrics: LyricsSnapshot = LyricsSnapshot("", LyricsStatus.UNSUPPORTED),
)
