package com.jaco.musicenhance.player.ui.lyrics

import android.graphics.RenderEffect
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.roundToInt

internal object LyricsAppearance {
    const val BLUR_DURATION_MS = 320L
    const val TEXT_FADE_DURATION_MS = 220L
    const val MAX_BLUR_DISTANCE = 4
    const val BLUR_RADIUS_DP_PER_LINE = 1.25f
    const val BLUR_STEPS_PER_LINE = 20
    // Space for glyph ascenders/descenders and the maximum 5 dp blur halo.
    const val BLUR_PADDING_DP = 12f

    fun distance(lineIndex: Int, activeLineIndex: Int) =
        abs(lineIndex - activeLineIndex.coerceAtLeast(0)).coerceAtMost(MAX_BLUR_DISTANCE)

    fun textAlpha(distance: Int, focused: Boolean, browsing: Boolean): Float = when {
        focused -> 1f
        browsing -> 0.82f
        else -> 0.64f - distance * 0.065f
    }
}

/** GPU effects are reused across rows and frames, including manual-blur transitions. */
internal class LyricBlurEffects(private val density: Float) {
    private val effects = arrayOfNulls<RenderEffect>(
        LyricsAppearance.MAX_BLUR_DISTANCE * LyricsAppearance.BLUR_STEPS_PER_LINE + 1,
    )

    fun step(blurLevel: Float) = (blurLevel * LyricsAppearance.BLUR_STEPS_PER_LINE)
        .roundToInt().coerceIn(effects.indices)

    fun at(step: Int): RenderEffect? {
        if (step == 0) return null
        return effects[step] ?: run {
            val radius = density * step * LyricsAppearance.BLUR_RADIUS_DP_PER_LINE / LyricsAppearance.BLUR_STEPS_PER_LINE
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).also { effects[step] = it }
        }
    }
}
