package vip.chener.musicenhance.adapter.lxx

import vip.chener.musicenhance.player.artwork.ArtworkProvider
import vip.chener.musicenhance.player.model.PlayerSnapshot

/** Borrow the host's already decoded bitmap. No module cache, URL rewriting or prefetch. */
internal class LxxArtworkSource(private val playback: LxxPlaybackSource) : ArtworkProvider {
    override val holdPreviousArtworkWhileLoading = false
    override fun snapshot(player: PlayerSnapshot) = playback.artwork().takeIf {
        LxxStateBridge.state.metadataKey == player.metadataKey
    }
    override fun release() = Unit // The bitmap belongs to the host; never recycle it here.
}
