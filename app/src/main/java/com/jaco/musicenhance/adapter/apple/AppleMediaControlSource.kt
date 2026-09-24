package com.jaco.musicenhance.adapter.apple

import android.content.res.Resources
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.jaco.musicenhance.player.media.MediaSessionControlReader
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.RepeatMode

/** 6.5.2 publishes the current favorite/repeat state through its media action icons. */
internal class AppleMediaControlSource(private val resources: Resources) : MediaSessionControlReader {
    private val repeatIcons = mapOf(
        icon("media_action_repeat_off") to RepeatMode.SEQUENTIAL,
        icon("media_action_repeat_all_on") to RepeatMode.LIST_LOOP,
        icon("media_action_repeat_one_on") to RepeatMode.SINGLE_LOOP,
    ).filterKeys { it != 0 }
    private val favoriteIcons = mapOf(icon("snackbar_favorite") to false, icon("snackbar_favorite_filled") to true).filterKeys { it != 0 }

    override fun read(metadata: MediaMetadata?, playback: PlaybackState?): PlayerControlState {
        val actions = playback?.customActions.orEmpty()
        return PlayerControlState(
            repeatMode = actions.firstOrNull { it.action == ACTION_REPEAT }?.icon?.let(repeatIcons::get) ?: RepeatMode.UNKNOWN,
            favorite = actions.firstOrNull { it.action == ACTION_FAVORITE }?.icon?.let(favoriteIcons::get),
            songTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        )
    }

    // R classes are removed by Apple's optimizer; resolve resource table entries instead.
    @Suppress("DiscouragedApi")
    private fun icon(name: String): Int = resources.getIdentifier(name, "drawable", ApplePlayerProfile.packageName)

    private companion object {
        const val ACTION_REPEAT = "com.apple.android.music.playback.action.REPEAT"
        const val ACTION_FAVORITE = "com.apple.android.music.playback.action.FAVORITE"
    }
}
