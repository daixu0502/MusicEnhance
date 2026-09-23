package com.jaco.musicenhance.player.model

import android.graphics.Bitmap

internal data class PlayerSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val actions: Long,
    val customActions: List<String>,
) {
    companion object {
        val Empty = PlayerSnapshot(
            title = "打开音乐应用开始播放",
            artist = "外屏播放器已就绪",
            album = "",
            artwork = null,
            durationMs = 0,
            positionMs = 0,
            isPlaying = false,
            actions = 0,
            customActions = emptyList(),
        )

    }
}

