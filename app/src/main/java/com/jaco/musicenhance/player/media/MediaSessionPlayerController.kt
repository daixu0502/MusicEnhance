package com.jaco.musicenhance.player.media

import android.graphics.Bitmap
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.artwork.ArtworkProvider
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Shared transport for media-session apps. Optional native features remain unknown by default. */
internal open class MediaSessionPlayerController(
    final override val appName: String,
    private val lyricsProvider: LyricsProvider = LyricsProvider.Unsupported,
    private val artworkProvider: ArtworkProvider = ArtworkProvider.Unsupported,
) : PlayerController {
    override fun snapshot() = MediaSessionStore.snapshot()
    override fun addListener(listener: (PlayerSnapshot) -> Unit) = MediaSessionStore.addListener(listener)
    override fun removeListener(listener: (PlayerSnapshot) -> Unit) = MediaSessionStore.removeListener(listener)
    override fun playPause() = MediaSessionStore.playPause()
    override fun previous() = MediaSessionStore.previous()
    override fun next() = MediaSessionStore.next()
    override fun seekTo(positionMs: Long) = MediaSessionStore.seekTo(positionMs)
    override fun seekAndPlay(positionMs: Long) = MediaSessionStore.seekAndPlay(positionMs)
    override fun lyrics(snapshot: PlayerSnapshot) = lyricsProvider.snapshot(snapshot)
    override fun release() {
        try {
            lyricsProvider.release()
        } finally {
            artworkProvider.release()
        }
    }
    override fun controlState() = PlayerControlState()
    override fun cycleRepeat() = MediaSessionStore.repeat()
    override fun toggleFavorite() = MediaSessionStore.favorite()
    override fun nativeArtwork(): Bitmap? = null
    override val hasArtworkProvider get() = artworkProvider !== ArtworkProvider.Unsupported
    override fun verifiedArtwork(snapshot: PlayerSnapshot) = artworkProvider.snapshot(snapshot)
    override fun bassLevel() = SpectrumEngine.bassSnapshot()
    override fun setSpectrumPlaybackActive(active: Boolean) = SpectrumEngine.setRemotePlaybackActive(active)
}
