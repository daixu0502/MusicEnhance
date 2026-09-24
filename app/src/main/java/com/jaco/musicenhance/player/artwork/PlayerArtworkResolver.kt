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

    fun resolve(snapshot: PlayerSnapshot, nowMs: Long): Result {
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
        const val NATIVE_ARTWORK_POLL_MS = 1_500L
    }
}
