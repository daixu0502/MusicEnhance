package com.jaco.musicenhance.adapter.apple

import com.jaco.musicenhance.player.artwork.PlaylistArtworkSource
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Workers only see immutable values copied from Media3 on its application thread. */
internal class AppleArtworkSource(private val playlist: () -> Playlist?) : PlaylistArtworkSource<AppleArtworkSource.Song> {
    data class Song(val id: String, val queueId: Long, val title: String, val artist: String, val album: String, val address: String) {
        val identity get() = "$id:$queueId"
    }
    data class Playlist(val current: Song, val neighbours: List<Song>)

    override fun currentSong(player: PlayerSnapshot): Song? = playlist()?.current?.takeIf {
        it.title == player.title.trim() && ApplePlaybackSource.matchesOptional(it.artist, player.artist) &&
            ApplePlaybackSource.matchesOptional(it.album, player.album)
    }
    override fun isCurrentSong(song: Song) = playlist()?.current?.identity == song.identity
    override fun cacheKey(song: Song) = "apple:${song.id}:${song.address}"
    override fun neighbours(song: Song) = playlist()?.takeIf { it.current.identity == song.identity }?.neighbours.orEmpty()
    override fun addresses(song: Song, isCurrent: () -> Boolean) = sequence {
        for (address in AppleArtworkAddresses.candidates(song.address)) {
            if (!isCurrent()) break
            yield(address)
        }
    }
}
