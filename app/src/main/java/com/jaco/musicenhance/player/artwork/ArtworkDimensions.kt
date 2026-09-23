package com.jaco.musicenhance.player.artwork

import kotlin.math.max
import kotlin.math.min

/** Reject tiny icons and banners, while accepting low-resolution album/singer fallbacks. */
internal object ArtworkDimensions {
    fun isUsable(width: Int, height: Int): Boolean {
        val shortSide = min(width, height)
        return shortSide >= 150 && shortSide.toFloat() / max(width, height) >= 0.72f
    }
}
