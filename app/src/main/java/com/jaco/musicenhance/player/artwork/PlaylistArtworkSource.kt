package com.jaco.musicenhance.player.artwork

import com.jaco.musicenhance.player.model.PlayerSnapshot

/** All methods run on background workers. Song values must be immutable snapshots. */
internal interface PlaylistArtworkSource<S> {
    fun currentSong(player: PlayerSnapshot): S?
    fun isCurrentSong(song: S): Boolean
    /** Globally namespaced native identity; metadata alone is not a song identifier. */
    fun cacheKey(song: S): String
    fun neighbours(song: S): List<S>
    /** Lazy, best quality first; individual lookup failures must allow the next candidate. */
    fun addresses(song: S, isCurrent: () -> Boolean): Sequence<String>
}
