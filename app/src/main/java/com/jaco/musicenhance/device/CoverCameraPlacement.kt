package com.jaco.musicenhance.device


/** Resolve camera geometry before falling back to rotation, which HyperOS may report as 0. */
internal object CoverCameraPlacement {
    data class Cutout(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val frameWidth: Int, val frameHeight: Int,
    ) {
        fun isCamera(): Boolean = frameWidth > 0 && frameHeight > 0 &&
            left >= 0 && top >= 0 && right <= frameWidth && bottom <= frameHeight &&
            right - left > frameWidth * 0.20f && bottom - top > frameHeight * 0.30f
    }

    data class Placement(val right: Boolean, val bottom: Boolean, val source: String)

    fun resolve(physical: Cutout?, window: Cutout?, rotation: Int): Placement {
        // A valid display cutout wins even if Activity insets are stale after a panel swap.
        val cutout = physical?.takeIf { it.isCamera() } ?: window?.takeIf { it.isCamera() }
        if (cutout != null) return Placement(
            cutout.left + cutout.right > cutout.frameWidth,
            cutout.top + cutout.bottom > cutout.frameHeight,
            if (cutout === physical) "display-cutout" else "window-cutout",
        )
        return Placement(rotation == 0 || rotation == 1, rotation == 0 || rotation == 3, "rotation")
    }
}
