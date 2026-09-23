package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerSnapshot

/**
 * Optional artwork verified against the requested song, including lower-resolution fallbacks.
 * Called on the UI thread: return cached data and perform IPC/download/decoding asynchronously.
 * release() invalidates pending work; the provider can be reused after view reattachment.
 */
internal interface ArtworkProvider {
    fun snapshot(player: PlayerSnapshot): Bitmap?
    fun release()

    object Unsupported : ArtworkProvider {
        override fun snapshot(player: PlayerSnapshot): Bitmap? = null
        override fun release() = Unit
    }
}
