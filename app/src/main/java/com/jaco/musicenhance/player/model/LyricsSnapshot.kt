package com.jaco.musicenhance.player.model

internal data class LyricLine(val startMs: Long, val text: String)

internal enum class LyricsStatus { LOADING, READY, UNAVAILABLE, UNSUPPORTED }

/**
 * Immutable, app-independent lyrics. Times use the media-session timeline in milliseconds.
 * trackKey is scoped to the provider and must change when native track identity changes,
 * even for two recordings with the same displayed metadata.
 */
internal data class LyricsSnapshot(
    val trackKey: String,
    val status: LyricsStatus,
    val lines: List<LyricLine> = emptyList(),
)
