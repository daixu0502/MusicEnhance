package com.jaco.musicenhance.adapter.lxx

import com.jaco.musicenhance.player.artwork.ArtworkProvider
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Borrow the host's already decoded bitmap. No module cache, URL rewriting or prefetch. */
internal class LxxArtworkSource(private val playback: LxxPlaybackSource) : ArtworkProvider {
    override val holdPreviousArtworkWhileLoading = false
    override fun snapshot(player: PlayerSnapshot) = playback.artwork().takeIf {
        LxxStateBridge.state.metadataKey == player.metadataKey
    }
    override fun release() = Unit // The bitmap belongs to the host; never recycle it here.
}
