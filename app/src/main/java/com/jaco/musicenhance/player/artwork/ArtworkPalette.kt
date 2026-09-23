package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import android.graphics.Color

/** Samples artwork hues and adjusts brightness for a visible spectrum on the camera rail. */
internal object ArtworkPalette {
    const val DEFAULT_SPECTRUM_COLOR = 0xFFE82A1F.toInt()

    fun spectrumColor(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_SPECTRUM_COLOR
        val hueWeights = FloatArray(24)
        val redSums = FloatArray(24)
        val greenSums = FloatArray(24)
        val blueSums = FloatArray(24)
        val hsv = FloatArray(3)
        val stepX = (bitmap.width / 36).coerceAtLeast(1)
        val stepY = (bitmap.height / 36).coerceAtLeast(1)
        var fallbackRed = 0f
        var fallbackGreen = 0f
        var fallbackBlue = 0f
        var fallbackWeight = 0f

        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 160) {
                    Color.colorToHSV(pixel, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (value in 0.10f..0.96f) {
                        val weight = (0.30f + saturation * 0.70f) * (0.45f + value * 0.55f)
                        fallbackRed += Color.red(pixel) * weight
                        fallbackGreen += Color.green(pixel) * weight
                        fallbackBlue += Color.blue(pixel) * weight
                        fallbackWeight += weight
                        if (saturation >= 0.10f) {
                            val bin = (hsv[0] / 15f).toInt().coerceIn(0, hueWeights.lastIndex)
                            hueWeights[bin] += weight
                            redSums[bin] += Color.red(pixel) * weight
                            greenSums[bin] += Color.green(pixel) * weight
                            blueSums[bin] += Color.blue(pixel) * weight
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }

        val dominantBin = hueWeights.indices.maxByOrNull { hueWeights[it] } ?: 0
        val dominantWeight = hueWeights[dominantBin]
        val color = if (dominantWeight > 0f) {
            Color.rgb(
                (redSums[dominantBin] / dominantWeight).toInt().coerceIn(0, 255),
                (greenSums[dominantBin] / dominantWeight).toInt().coerceIn(0, 255),
                (blueSums[dominantBin] / dominantWeight).toInt().coerceIn(0, 255),
            )
        } else if (fallbackWeight > 0f) {
            Color.rgb(
                (fallbackRed / fallbackWeight).toInt().coerceIn(0, 255),
                (fallbackGreen / fallbackWeight).toInt().coerceIn(0, 255),
                (fallbackBlue / fallbackWeight).toInt().coerceIn(0, 255),
            )
        } else {
            return DEFAULT_SPECTRUM_COLOR
        }

        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.12f) hsv[1] = 0.08f else hsv[1] = hsv[1].coerceAtLeast(0.42f)
        hsv[2] = hsv[2].coerceIn(0.72f, 0.98f)
        return Color.HSVToColor(hsv)
    }

}
