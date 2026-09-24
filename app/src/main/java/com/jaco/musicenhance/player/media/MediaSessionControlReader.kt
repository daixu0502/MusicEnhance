package com.jaco.musicenhance.player.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.jaco.musicenhance.player.model.PlayerControlState

/** Injected by the app adapter; private keys and icon meanings stay outside the media layer. */
internal fun interface MediaSessionControlReader {
    fun read(metadata: MediaMetadata?, playback: PlaybackState?): PlayerControlState
}
