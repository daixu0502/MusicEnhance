package vip.chener.musicenhance.adapter

import vip.chener.musicenhance.player.PlayerDataSource
import vip.chener.musicenhance.player.PlayerSession
import vip.chener.musicenhance.player.artwork.ArtworkProvider
import vip.chener.musicenhance.player.lyrics.LyricsProvider
import vip.chener.musicenhance.player.media.MediaSessionDataSource

/** Common session assembly; no app-specific reflection or window ownership. */
internal fun nativePlayerSession(
    appName: String,
    lyrics: LyricsProvider,
    artwork: ArtworkProvider,
    repeatApi: () -> NativeRepeatControl.Api,
    favorite: NativeFavoriteControl? = null,
): PlayerSession {
    var repeat: NativeRepeatControl? = null
    fun repeatControl() = repeat ?: NativeRepeatControl(repeatApi).also { repeat = it }
    val media = MediaSessionDataSource
    return PlayerSession(
        appName,
        object : PlayerDataSource by media {
            override fun controlState() = (favorite?.read(snapshot()) ?: media.controlState())
                .copy(repeatMode = repeatControl().snapshot())
        },
        media.actions.copy(
            cycleRepeat = { repeatControl().advance() },
            toggleFavorite = { favorite?.toggle(media.snapshot()) ?: media.actions.toggleFavorite() },
        ),
        lyrics, artwork,
        onRelease = {
            try { repeat?.release(); repeat = null } finally { favorite?.release() }
        },
    )
}
