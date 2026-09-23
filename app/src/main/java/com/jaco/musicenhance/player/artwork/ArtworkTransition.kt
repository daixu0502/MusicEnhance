package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap

/** A bounded visual bridge, separate from the current song's artwork selection/cache. */
internal class ArtworkTransition {
    private var previousBackground: Bitmap? = null
    private var expiresAtMs = 0L

    fun begin(displayed: Bitmap?, nowMs: Long) {
        previousBackground = displayed?.takeUnless { it.isRecycled }
        expiresAtMs = nowMs + MAX_WAIT_MS
    }

    fun background(current: Bitmap?, ready: Boolean, nowMs: Long): Bitmap? {
        if (ready || nowMs >= expiresAtMs || previousBackground?.isRecycled == true) previousBackground = null
        return previousBackground ?: current
    }

    companion object {
        const val MAX_WAIT_MS = 1_500L
    }
}
