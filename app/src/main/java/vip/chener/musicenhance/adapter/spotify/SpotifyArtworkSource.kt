package vip.chener.musicenhance.adapter.spotify

import vip.chener.musicenhance.player.artwork.PlaylistArtworkSource
import vip.chener.musicenhance.player.model.PlayerSnapshot

internal class SpotifyArtworkSource : PlaylistArtworkSource<SpotifyApi.Song> {
    override fun currentSong(player: PlayerSnapshot) = SpotifyApi.current(player)?.song
    override fun isCurrentSong(song: SpotifyApi.Song) = SpotifyApi.state()?.song?.uri == song.uri
    override fun cacheKey(song: SpotifyApi.Song) = song.uri

    override fun neighbours(song: SpotifyApi.Song): List<SpotifyApi.Song> {
        val state = SpotifyApi.state()?.takeIf { it.song?.uri == song.uri } ?: return emptyList()
        return (0..2).flatMap { listOfNotNull(state.previous.getOrNull(it), state.next.getOrNull(it)) }
            .filter { it.uri != song.uri }.distinctBy { it.uri }
    }

    override fun addresses(song: SpotifyApi.Song, isCurrent: () -> Boolean): Sequence<String> =
        candidates(song.metadata).asSequence().takeWhile { isCurrent() }

    companion object {
        private val imageId = Regex("[a-fA-F0-9]{40}")
        fun candidates(metadata: Map<String, String>): List<String> =
            listOf("image_xlarge_url", "image_large_url", "image_url", "image_small_url")
                .mapNotNull { metadata[it]?.let(::normalize) }.distinct()

        private fun normalize(address: String): String? {
            if (address.startsWith("spotify:image:")) {
                val id = address.removePrefix("spotify:image:")
                return if (imageId.matches(id)) "https://i.scdn.co/image/$id" else null
            }
            return address.takeIf { it.startsWith("https://") && it.length > 8 }
        }
    }
}
