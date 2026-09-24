package com.jaco.musicenhance.adapter.apple

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.player.media.MediaSessionPlayerController
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.RepeatMode

internal class AppleMusicPlayerController(activity: Activity, private val root: ViewGroup) :
    MediaSessionPlayerController(AppleMusicProfile.displayName, AppleLyricsProvider(activity.application)) {

    override fun nativeArtwork() = NativePlayerViews.findArtwork(root)

    override fun controlState(): PlayerControlState {
        val player = snapshot()
        val item = AppleNativePlayer.item(player)
        val favorite = runCatching {
            when (item?.javaClass?.getMethod("getLikeState")?.invoke(item) as? Int) {
                2 -> true
                1, 3 -> false
                else -> null
            }
        }.getOrNull()
        val mode = runCatching {
            val browser = AppleNativePlayer.browser() ?: return@runCatching RepeatMode.UNKNOWN
            when (browser.javaClass.getMethod("getRepeatMode").invoke(browser) as? Int) {
                0 -> RepeatMode.SEQUENTIAL
                1 -> RepeatMode.SINGLE_LOOP
                2 -> RepeatMode.LIST_LOOP
                else -> RepeatMode.UNKNOWN
            }
        }.getOrDefault(RepeatMode.UNKNOWN)
        val fallback = super.controlState()
        return fallback.copy(
            repeatMode = if (mode == RepeatMode.UNKNOWN) fallback.repeatMode else mode,
            favorite = favorite ?: favoriteButton()?.let { button ->
                when (button.contentDescription?.toString()) {
                    favoriteLabel -> false
                    unfavoriteLabel -> true
                    else -> null
                }
            } ?: fallback.favorite,
            songTitle = player.title,
        )
    }

    override fun cycleRepeat(): Boolean = runCatching {
        val browser = AppleNativePlayer.browser() ?: return super.cycleRepeat()
        val current = browser.javaClass.getMethod("getRepeatMode").invoke(browser) as? Int ?: return false
        // Media3 repeat modes: off=0, one=1, all=2. Use the native three-state sequence.
        val next = when (current) { 0 -> 2; 2 -> 1; 1 -> 0; else -> return false }
        browser.javaClass.getMethod("setRepeatMode", Int::class.javaPrimitiveType).invoke(browser, next)
        true
    }.getOrDefault(false)

    override fun toggleFavorite(): Boolean = favoriteButton()?.performClick() ?: super.toggleFavorite()

    private val favoriteLabel = stringResource("favorite_button")
    private val unfavoriteLabel = stringResource("undo_favorite_button")

    private fun favoriteButton() = NativePlayerViews.find(root) {
        val description = it.contentDescription?.toString()
        it.isClickable && it.isEnabled && description != null &&
            (description == favoriteLabel || description == unfavoriteLabel)
    }

    @Suppress("DiscouragedApi")
    private fun stringResource(name: String): String? {
        val id = root.resources.getIdentifier(name, "string", AppleMusicProfile.packageName)
        return if (id == 0) null else root.resources.getString(id)
    }
}
