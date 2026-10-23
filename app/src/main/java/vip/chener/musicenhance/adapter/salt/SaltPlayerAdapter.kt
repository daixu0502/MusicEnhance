package vip.chener.musicenhance.adapter.salt

import android.app.Activity
import vip.chener.musicenhance.adapter.CachedNativeLyricsProvider
import vip.chener.musicenhance.adapter.MusicPlayerAdapter
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.player.PlayerDataSource
import vip.chener.musicenhance.player.PlayerSession
import vip.chener.musicenhance.player.media.MediaSessionDataSource
import vip.chener.musicenhance.player.model.PlayerControlState

internal object SaltPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = SaltPlayerProfile
    override fun installHooks(loader: ClassLoader) {
        HostInterfaces.preload(loader, "salt.capabilities") {
            val controls = SaltControlApi(loader)
            val data = SaltDataApi(loader)
            val capabilities = listOf<() -> Any>(
                { controls.callback }, { controls.currentSong }, { controls.play }, { controls.pause },
                { controls.next }, { controls.previous }, { controls.seek }, { controls.playing },
                { controls.repeat }, { controls.repeatFlow }, { controls.cueOffset },
                { data.documentType }, { data.lyricPublish }, { data.lyricsFlow }, { data.lyricsOwnerFlow },
                { data.lineType }, { data.lineStart }, { data.lineText }, { data.artworkPublish },
                { data.coverPair }, { data.coverPresent }, { data.coverBitmap }, { data.coverFlow },
                { SaltSheetApi(loader) },
            )
            capabilities.forEach { runCatching(it).onFailure { error ->
                if (error !is HostInterfaces.Unavailable || error.message?.contains("pending") != true) {
                    moduleInfo("Salt capability preparation: ${error.message}")
                }
            } }
        }
        SaltPlayerHooks.install(loader)
        SaltNativeDataHooks.install(loader)
    }
    override fun onActivityReady(activity: Activity) = SaltPlayerHooks.observe(activity)

    override fun createSession(activity: Activity): PlayerSession {
        val playback = SaltPlaybackSource(activity.classLoader)
        val media = MediaSessionDataSource
        val lyrics = SaltLyricsSource(playback)
        val data = object : PlayerDataSource by media {
            // Notification metadata may still carry the outgoing thumbnail during a track change.
            override fun snapshot() = media.snapshot().copy(artwork = null)
            override fun controlState() = PlayerControlState(
                repeatMode = playback.repeatMode(), favorite = true, songTitle = snapshot().title,
            )
        }
        return PlayerSession(
            profile.displayName, data,
            media.actions.copy(
                playPause = playback::playPause, previous = playback::previous, next = playback::next,
                seekTo = { playback.seekTo(it, data.snapshot(), resume = false) },
                seekAndPlay = { playback.seekTo(it, data.snapshot(), resume = true) },
                cycleRepeat = playback::cycleRepeat,
                // Requested display policy for local music; do not alter Salt's actual favorites.
                toggleFavorite = { false },
            ),
            CachedNativeLyricsProvider(lyrics::readLyrics), SaltArtworkSource(playback),
            onRelease = playback::release,
        )
    }
}
