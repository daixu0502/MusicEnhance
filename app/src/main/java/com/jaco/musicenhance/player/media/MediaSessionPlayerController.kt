package com.jaco.musicenhance.player.media

import android.graphics.Bitmap
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Shared transport for media-session apps. Optional native features remain unknown by default. */
internal open class MediaSessionPlayerController(final override val appName: String) : PlayerController {
    override fun snapshot() = MediaSessionStore.snapshot()
    override fun addListener(listener: (PlayerSnapshot) -> Unit) = MediaSessionStore.addListener(listener)
    override fun removeListener(listener: (PlayerSnapshot) -> Unit) = MediaSessionStore.removeListener(listener)
    override fun playPause() = MediaSessionStore.playPause()
    override fun previous() = MediaSessionStore.previous()
    override fun next() = MediaSessionStore.next()
    override fun seekTo(positionMs: Long) = MediaSessionStore.seekTo(positionMs)
    override fun controlState() = PlayerControlState()
    override fun cycleRepeat() = MediaSessionStore.repeat()
    override fun toggleFavorite() = MediaSessionStore.favorite()
    override fun nativeArtwork(): Bitmap? = null
    override fun highResolutionArtwork(snapshot: PlayerSnapshot): Bitmap? = null
    override fun bassLevel() = SpectrumEngine.bassSnapshot()
    override fun setSpectrumPlaybackActive(active: Boolean) = SpectrumEngine.setRemotePlaybackActive(active)
}
