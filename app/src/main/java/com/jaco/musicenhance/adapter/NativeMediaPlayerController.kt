package com.jaco.musicenhance.adapter

import android.graphics.Bitmap
import android.view.ViewGroup
import com.jaco.musicenhance.player.artwork.ArtworkProvider
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.media.MediaSessionPlayerController

/** Shared native artwork and optional repeat control; app-specific APIs are injected by the factory. */
internal class NativeMediaPlayerController(
    appName: String,
    private val root: ViewGroup,
    lyricsProvider: LyricsProvider = LyricsProvider.Unsupported,
    private val repeatApi: (() -> NativeRepeatControl.Api)? = null,
    private val artworkReader: (() -> Bitmap?)? = null,
    private val favoriteControl: NativeFavoriteControl? = null,
    artworkProvider: ArtworkProvider = ArtworkProvider.Unsupported,
) : MediaSessionPlayerController(appName, lyricsProvider, artworkProvider) {
    private var repeatControl: NativeRepeatControl? = null

    override fun nativeArtwork() = if (artworkReader != null) artworkReader.invoke() else NativePlayerViews.findArtwork(root)

    override fun controlState() = super.controlState().let { mediaState ->
        val favoriteState = favoriteControl?.read(snapshot()) ?: mediaState
        val factory = repeatApi ?: return@let favoriteState
        // A View can reattach after a window transition. Lazily recreate the released worker.
        val repeat = repeatControl ?: NativeRepeatControl(factory).also { repeatControl = it }
        favoriteState.copy(repeatMode = repeat.snapshot())
    }

    override fun cycleRepeat() = if (repeatApi == null) super.cycleRepeat() else repeatControl?.advance() ?: false

    override fun toggleFavorite() = favoriteControl?.toggle(snapshot()) ?: super.toggleFavorite()

    override fun release() {
        repeatControl?.release()
        repeatControl = null
        super.release()
    }
}
