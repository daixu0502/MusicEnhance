package vip.chener.musicenhance.adapter.kugou

import android.app.Activity
import vip.chener.musicenhance.adapter.CachedNativeLyricsProvider
import vip.chener.musicenhance.adapter.MusicPlayerAdapter
import vip.chener.musicenhance.adapter.PolledFavoriteControl
import vip.chener.musicenhance.adapter.nativePlayerSession
import vip.chener.musicenhance.player.PlayerSession
import vip.chener.musicenhance.player.artwork.ArtworkDiskCache
import vip.chener.musicenhance.player.artwork.PlaylistArtworkProvider
import java.io.File

internal object KugouPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = KugouPlayerProfile
    override fun installHooks(loader: ClassLoader) = KugouPlayerHooks.install(loader)
    override fun onActivityReady(activity: Activity) = KugouPlayerHooks.observe(activity)
    override fun createSession(activity: Activity): PlayerSession {
        val playback = KugouPlaybackSource(activity.classLoader)
        val lyrics = KugouLyricsSource(activity.classLoader)
        val session = nativePlayerSession(profile.displayName,
            lyrics = CachedNativeLyricsProvider(lyrics::readLyrics),
            artwork = PlaylistArtworkProvider("Kugou", { KugouArtworkSource(activity.classLoader) },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork"))),
            repeatApi = { KugouRepeatSource(activity.classLoader) },
            favorite = PolledFavoriteControl({ KugouFavoriteSource(activity.classLoader, KugouPlayerHooks::homeActivity) }),
        )
        return session.copy(actions = session.actions.copy(seekAndPlay = { playback.seekAndPlay(it, session.data.snapshot()) }),
            onRelease = { try { playback.release(); lyrics.release() } finally { session.onRelease() } })
    }
}
