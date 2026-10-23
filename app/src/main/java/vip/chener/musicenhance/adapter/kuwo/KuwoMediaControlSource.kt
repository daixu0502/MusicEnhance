package vip.chener.musicenhance.adapter.kuwo

import android.media.MediaMetadata
import android.media.session.PlaybackState
import vip.chener.musicenhance.player.media.MediaSessionControlReader
import vip.chener.musicenhance.player.model.PlayerControlState

/** Android 13+ actions in Kuwo 12.2.2.4 explicitly describe favorite state. */
internal object KuwoMediaControlSource : MediaSessionControlReader {
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
