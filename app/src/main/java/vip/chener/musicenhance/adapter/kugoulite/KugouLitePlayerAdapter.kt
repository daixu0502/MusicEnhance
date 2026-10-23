package vip.chener.musicenhance.adapter.kugoulite

import vip.chener.musicenhance.adapter.PolledFavoriteControl
import android.app.Activity
import vip.chener.musicenhance.adapter.CachedNativeLyricsProvider
import vip.chener.musicenhance.adapter.MusicPlayerAdapter
import vip.chener.musicenhance.adapter.nativePlayerSession
import vip.chener.musicenhance.player.PlayerSession
import vip.chener.musicenhance.player.artwork.ArtworkDiskCache
import vip.chener.musicenhance.player.artwork.PlaylistArtworkProvider
import java.io.File

internal object KugouLitePlayerAdapter : MusicPlayerAdapter {
    override val profile get() = KugouLitePlayerProfile
    override fun installHooks(loader: ClassLoader) {
        KugouLitePlayerHooks.install(loader)
        KugouLiteLyricsSource.install(loader)
    }
    override fun onActivityReady(activity: Activity) = KugouLitePlayerHooks.observe(activity)
    override fun createSession(activity: Activity): PlayerSession {
        val playback = KugouLitePlaybackSource(activity.classLoader)
        val session = nativePlayerSession(
            profile.displayName,
            lyrics = CachedNativeLyricsProvider(KugouLiteLyricsSource.Reader(activity.classLoader)::readLyrics),
            artwork = PlaylistArtworkProvider(
                "Kugou Lite", { KugouLiteArtworkSource(activity.classLoader) },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
            ),
            repeatApi = { KugouLiteRepeatSource(activity.classLoader) },
            favorite = PolledFavoriteControl({ KugouLiteFavoriteSource(activity.classLoader, KugouLitePlayerHooks::homeActivity) }),
        )
        return session.copy(
            actions = session.actions.copy(seekAndPlay = { playback.seekAndPlay(it, session.data.snapshot()) }),
            onRelease = { try { playback.release() } finally { session.onRelease() } },
        )
    }
}
