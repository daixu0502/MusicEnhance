package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Per-session source selection; providers own downloads, caches and playlist prefetch. */
internal class PlayerArtworkResolver(
    private val provider: ArtworkProvider,
    private val readNativeArtwork: () -> Bitmap?,
) {
    data class Result(val background: Bitmap?, val thumbnail: Bitmap?)

    private val selection = PlayerArtworkState()
    private val transition = ArtworkTransition()
    private var background: Bitmap? = null
    private var nativeArtwork: Bitmap? = null
    private var nextNativePollAtMs = 0L
    private var lastProvidedArtwork: Bitmap? = null

    fun resolve(snapshot: PlayerSnapshot, nowMs: Long): Result {
        if (provider.ownsArtworkSelection) {
            val image = provider.snapshot(snapshot)?.takeUnless { it.isRecycled }
            if (image == null && lastProvidedArtwork != null) {
                transition.begin(lastProvidedArtwork, nowMs, PREVIEW_WAIT_MS)
            }
            // Bridge only the background across a short asynchronous cache/preview read.
            // Never publish the outgoing image as the new song's thumbnail, extend the
            // deadline on repeated null results, or keep it indefinitely for a missing cover.
            lastProvidedArtwork = image
            return Result(transition.background(image, image != null, nowMs), image)
        }
        if (selection.updateTrack(snapshot)) {
            transition.begin(background, nowMs)
            nativeArtwork = null
            nextNativePollAtMs = 0L
        }
        if (nowMs >= nextNativePollAtMs) {
            nextNativePollAtMs = nowMs + NATIVE_ARTWORK_POLL_MS
            readNativeArtwork()?.takeUnless { it.isRecycled }?.takeIf {
                ArtworkDimensions.isUsable(it.width, it.height)
            }?.let { nativeArtwork = it }
        }
        val verified = provider.snapshot(snapshot)?.takeUnless { it.isRecycled }
        val selected = selection.select(verified, snapshot.artwork, nativeArtwork)
        val ready = verified != null || (!provider.holdPreviousArtworkWhileLoading && selected != null)
        background = transition.background(selected, ready, nowMs)
        return Result(background, selected)
    }

    private companion object {
        const val PREVIEW_WAIT_MS = 350L
        const val NATIVE_ARTWORK_POLL_MS = 1_500L
    }
}
