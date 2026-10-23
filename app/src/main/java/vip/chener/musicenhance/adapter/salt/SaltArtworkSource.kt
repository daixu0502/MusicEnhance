package vip.chener.musicenhance.adapter.salt

import vip.chener.musicenhance.player.artwork.ArtworkProvider
import vip.chener.musicenhance.player.model.PlayerSnapshot

/** Salt owns decoding and cache lifetime. Never recycle its shared Bitmap or fetch online art. */
internal class SaltArtworkSource(private val playback: SaltPlaybackSource) : ArtworkProvider {
    override val holdPreviousArtworkWhileLoading = false
    override fun snapshot(player: PlayerSnapshot) = playback.artwork(player)
    override fun release() = Unit
}
