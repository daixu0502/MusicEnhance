package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerSnapshot

/**
 * Optional artwork verified against the requested song, including lower-resolution fallbacks.
 * Called on the UI thread: return cached data and perform IPC/download/decoding asynchronously.
 * release() invalidates pending work; the provider can be reused after view reattachment.
 */
internal interface ArtworkProvider {
    /** Hosts whose metadata briefly contains the previous cover may hold the outgoing frame. */
    val holdPreviousArtworkWhileLoading: Boolean get() = true
    fun snapshot(player: PlayerSnapshot): Bitmap?
    fun release()

    object Unsupported : ArtworkProvider {
        override val holdPreviousArtworkWhileLoading = false
        override fun snapshot(player: PlayerSnapshot): Bitmap? = null
        override fun release() = Unit
    }
}
