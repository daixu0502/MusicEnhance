package com.jaco.musicenhance.player.media

import com.jaco.musicenhance.player.PlayerActions
import com.jaco.musicenhance.player.PlayerDataSource
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.model.PlayerSnapshot

internal object MediaSessionDataSource : PlayerDataSource {
    override fun snapshot() = MediaSessionStore.snapshot()
    override fun addListener(listener: (PlayerSnapshot) -> Unit) = MediaSessionStore.addListener(listener)
    override fun removeListener(listener: (PlayerSnapshot) -> Unit) = MediaSessionStore.removeListener(listener)
    override fun bassLevel() = SpectrumEngine.bassSnapshot()

    // Do not initialize service bridges/audio workers merely by assembling a session.
    val actions = PlayerActions(
        playPause = { MediaSessionStore.playPause() },
        previous = { MediaSessionStore.previous() },
        next = { MediaSessionStore.next() },
        seekTo = { MediaSessionStore.seekTo(it) },
        seekAndPlay = { MediaSessionStore.seekAndPlay(it) },
        cycleRepeat = { MediaSessionStore.repeat() },
        toggleFavorite = { MediaSessionStore.favorite() },
        setSpectrumPlaybackActive = { SpectrumEngine.setRemotePlaybackActive(it) },
    )
}
