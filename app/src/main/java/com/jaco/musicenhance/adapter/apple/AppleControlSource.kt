package com.jaco.musicenhance.adapter.apple

import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode

internal class AppleControlSource(
    private val playback: ApplePlaybackSource,
    private val snapshot: () -> PlayerSnapshot = MediaSessionDataSource::snapshot,
) {
    private var lastReadFailure: String? = null

    fun controlState(): PlayerControlState {
        val player = snapshot()
        return runCatching {
            val item = playback.item(player)
            val favorite = playback.favoriteState(item)
            val mode = if (!playback.canSetRepeat()) RepeatMode.UNKNOWN else if (playback.shuffleEnabled()) RepeatMode.SHUFFLE else when (playback.repeatMode()) {
                0 -> RepeatMode.SEQUENTIAL
                1 -> RepeatMode.SINGLE_LOOP
                2 -> RepeatMode.LIST_LOOP
                else -> RepeatMode.UNKNOWN
            }
            PlayerControlState(mode, favorite, player.title, favoritePending = playback.favoritePending)
        }.onSuccess { lastReadFailure = null }.getOrElse {
            val failure = (it.cause ?: it).toString()
            if (failure != lastReadFailure) moduleInfo("Apple Music controls unavailable: $failure")
            lastReadFailure = failure
            PlayerControlState(songTitle = player.title)
        }
    }

    fun cycleRepeat(): Boolean = runCatching {
        val shuffle = !playback.shuffleEnabled() && playback.repeatMode() == 1
        val next = if (playback.shuffleEnabled()) 0 else when (playback.repeatMode()) {
            0 -> 2
            2 -> 1
            1 -> 0
            else -> return false
        }
        playback.setRepeatMode(next, shuffle).also {
            moduleInfo("Apple Music repeat request: target=$next, shuffle=$shuffle, accepted=$it, actual=${playback.repeatMode()}, actualShuffle=${playback.shuffleEnabled()}")
        }
    }.onFailure { moduleInfo("Apple Music repeat failed: ${it.cause ?: it}") }.getOrDefault(false)

    fun toggleFavorite(): Boolean = runCatching {
        controlState()
        val item = playback.item(snapshot()) ?: return false
        val favorite = playback.favoriteState(item) ?: return false
        if (!playback.toggleFavorite(item, favorite)) return false
        moduleInfo("Apple Music native star action: previous=$favorite")
        // The icon only follows the native item's confirmed state, never the requested value.
        true
    }.onFailure { moduleInfo("Apple Music favorite failed: ${it.cause ?: it}") }.getOrDefault(false)

}
