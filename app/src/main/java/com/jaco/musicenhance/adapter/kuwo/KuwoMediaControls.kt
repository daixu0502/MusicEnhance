package com.jaco.musicenhance.adapter.kuwo

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.jaco.musicenhance.player.media.MediaSessionControlReader
import com.jaco.musicenhance.player.model.PlayerControlState

/** Android 13+ actions in Kuwo 12.2.2.4 explicitly describe favorite state. */
internal object KuwoMediaControls : MediaSessionControlReader {
    override fun read(metadata: MediaMetadata?, playback: PlaybackState?) = PlayerControlState(
        favorite = decodeFavorite(playback?.customActions.orEmpty().map { it.action }),
        songTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
    )

    fun decodeFavorite(actions: List<String>): Boolean? = when {
        "KW_CUSTOM_EVENT_UNSUPPORTED_FAV" in actions -> null
        "KW_CUSTOM_EVENT_CANCEL_FAV" in actions && "KW_CUSTOM_EVENT_FAV" in actions -> null
        "KW_CUSTOM_EVENT_CANCEL_FAV" in actions -> true
        "KW_CUSTOM_EVENT_FAV" in actions -> false
        else -> null
    }
}
