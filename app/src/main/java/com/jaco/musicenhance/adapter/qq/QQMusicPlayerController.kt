package com.jaco.musicenhance.adapter.qq

import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.player.media.MediaSessionPlayerController
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** All QQ-specific native APIs are assembled here; the player UI never imports QQ classes. */
internal class QQMusicPlayerController(classLoader: ClassLoader, private val root: ViewGroup) :
    MediaSessionPlayerController(QQMusicProfile.displayName) {
    private val nativeControls = QQNativeControls(classLoader)
    private val albumArtwork = QQAlbumArtwork(classLoader)

    override fun controlState() = nativeControls.snapshot()
    override fun cycleRepeat() = nativeControls.cycleRepeat()
    override fun nativeArtwork() = NativePlayerViews.findArtwork(root)
    override fun highResolutionArtwork(snapshot: PlayerSnapshot) = albumArtwork.snapshot(snapshot)
    override fun toggleFavorite() = NativePlayerViews.clickControl(root, FAVORITE_KEYWORDS) || super.toggleFavorite()

    private companion object {
        val FAVORITE_KEYWORDS = listOf("favorite", "favourite", "collect", "like", "love", "fav", "收藏", "喜欢", "爱心")
    }
}
