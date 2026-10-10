package com.jaco.musicenhance.adapter.spotify

import android.app.Activity
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.player.PlayerDataSource
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.audio.AudioSessionSpectrumSource
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkProvider
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import java.io.File

internal object SpotifyPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = SpotifyPlayerProfile
    override fun installHooks(loader: ClassLoader) = SpotifyPlayerHooks.install(loader)
    override fun onActivityReady(activity: Activity) = SpotifyPlayerHooks.observe(activity)

    override fun createSession(activity: Activity): PlayerSession {
        val media = MediaSessionDataSource
        val controls = SpotifyControlSource()
        val spectrum = AudioSessionSpectrumSource(activity.applicationContext)
        val audioListener: (SpotifyAudioHooks.State) -> Unit = { state ->
            spectrum.setAudioSession(state.sessionId.takeIf { state.created && state.playing } ?: 0)
        }
        SpotifyAudioHooks.addListener(audioListener)
        val data = object : PlayerDataSource by media {
            override fun controlState() = controls.read(media.snapshot())
            override fun bassLevel() = maxOf(media.bassLevel(), spectrum.bassSnapshot())
        }
        return PlayerSession(
            profile.displayName, data,
            media.actions.copy(
                cycleRepeat = controls::cycleRepeat, toggleFavorite = controls::toggleFavorite,
                setSpectrumPlaybackActive = { active ->
                    media.actions.setSpectrumPlaybackActive(active)
                    spectrum.setPlaybackActive(active)
                },
            ),
            SpotifyLyricsProvider(activity.classLoader),
            PlaylistArtworkProvider(
                "Spotify", { SpotifyArtworkSource() },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
            ),
            onRelease = {
                SpotifyAudioHooks.removeListener(audioListener)
                spectrum.release()
            },
        )
    }
}
