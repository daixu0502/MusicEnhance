package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerSnapshot
import kotlin.math.min

/** Retains artwork for the current song while metadata/image sources update independently. */
internal class PlayerArtworkState {
    private var title: String? = null
    private var artist = ""
    private var album = ""
    private var selected: Bitmap? = null
    private var hasVerifiedArtwork = false

    /** An omitted album is incomplete metadata, not evidence that the song changed. */
    fun updateTrack(snapshot: PlayerSnapshot): Boolean {
        val changed = title != snapshot.title || artist != snapshot.artist ||
            (album.isNotBlank() && snapshot.album.isNotBlank() && album != snapshot.album)
        if (changed) {
            selected = null
            hasVerifiedArtwork = false
            album = ""
        }
        title = snapshot.title
        artist = snapshot.artist
        if (snapshot.album.isNotBlank()) album = snapshot.album
        return changed
    }

    fun select(verified: Bitmap?, metadata: Bitmap?, native: Bitmap?): Bitmap? {
        if (selected?.isRecycled == true) {
            selected = null
            hasVerifiedArtwork = false
        }
        verified?.takeUnless { it.isRecycled }?.let {
            // This source is checked against the playing song by the adapter. Do not let
            // an unlabelled native view/placeholder overwrite it during a later poll.
            selected = it
            hasVerifiedArtwork = true
        }
        if (!hasVerifiedArtwork) {
            selectLargerFallback(metadata)
            selectLargerFallback(native)
        }
        return selected
    }

    private fun selectLargerFallback(candidate: Bitmap?) {
        if (candidate == null || candidate.isRecycled) return
        val current = selected
        if (current == null || min(candidate.width, candidate.height) > min(current.width, current.height)) {
            selected = candidate
        }
    }
}
