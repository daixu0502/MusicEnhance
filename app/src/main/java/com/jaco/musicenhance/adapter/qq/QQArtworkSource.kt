package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.player.artwork.PlaylistArtworkSource
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** QQ native objects stay inside the adapter. All methods may perform IPC; use worker threads. */
internal interface QQArtworkSource : PlaylistArtworkSource<QQArtworkSource.Song> {
    data class Song(val nativeObject: Any, val id: Long)

    fun currentSong(expectedTitle: String): Song?
    override fun isCurrentSong(song: Song): Boolean
    override fun neighbours(song: Song): List<Song>
    override fun currentSong(player: PlayerSnapshot): Song? = currentSong(player.title)?.takeIf { it.id > 0 }
    override fun cacheKey(song: Song) = "qq:${song.id}"
    override fun addresses(song: Song, isCurrent: () -> Boolean): Sequence<String> =
        QQArtworkAddresses.candidates({ coverAddress(song, it) }, { singerAddress(song, it) }, isCurrent)
    fun singerAddress(currentSong: Song, size: QQArtworkAddresses.Size): String?
    fun coverAddress(currentSong: Song, size: QQArtworkAddresses.Size): String?
}
