package com.jaco.musicenhance.adapter.qq

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import androidx.core.graphics.scale
import com.jaco.musicenhance.hook.moduleInfo
import kotlin.math.abs

/** QQ 20.8.5.8 player/notification placeholders, including copied MediaSession bitmaps.
 * Called only by the artwork preview worker. No host bitmap is modified or recycled.
 */
internal class QQArtworkFilter(private val loadPlaceholders: () -> List<Bitmap>) {
    constructor(resources: Resources) : this({ loadPlaceholders(resources) })

    private val templates by lazy {
        loadPlaceholders().map { bitmap ->
            try { sample(bitmap) } finally { bitmap.recycle() }
        }
    }
    private val decisions = LruCache<Int, Boolean>(32)

    fun accepts(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val generation = bitmap.generationId
        decisions.get(generation)?.let { return it }
        val pixels = sample(bitmap)
        val accepted = templates.none { matches(pixels, it) }
        if (!bitmap.isRecycled && bitmap.generationId == generation) decisions.put(generation, accepted)
        return accepted
    }

    private fun sample(bitmap: Bitmap): IntArray {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        } else bitmap
        try {
            val scaled = software.scale(SAMPLE_SIZE, SAMPLE_SIZE)
            try {
                return IntArray(SAMPLE_SIZE * SAMPLE_SIZE).also {
                    scaled.getPixels(it, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
                }
            } finally {
                if (scaled !== software) scaled.recycle()
            }
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    private fun matches(pixels: IntArray, template: IntArray): Boolean {
        var total = 0L
        var centre = 0L
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val expected = template[index]
            val difference = abs(Color.red(pixel) - Color.red(expected)) +
                abs(Color.green(pixel) - Color.green(expected)) +
                abs(Color.blue(pixel) - Color.blue(expected)) +
                abs(Color.alpha(pixel) - Color.alpha(expected))
            total += difference
            if (index % SAMPLE_SIZE in 8..23 && index / SAMPLE_SIZE in 8..23) centre += difference
        }
        // Check the logo region separately: a plain grey/monochrome album must not match
        // just because it shares the placeholder's large, nearly uniform background.
        return total <= pixels.size * 4L * 3 && centre <= 16L * 16 * 4 * 3
    }

    private companion object {
        const val SAMPLE_SIZE = 32
        val RESOURCE_NAMES = listOf(
            "player_album_cover_default", "player_album_cover_default_dark", "player_albumcover_default",
            "notification_default_cover", "default_album_mid", "default_album_in_send_song",
        )

        // Host resources belong to QQ, not the module; resolve names instead of version-specific IDs.
        @SuppressLint("DiscouragedApi")
        fun loadPlaceholders(resources: Resources): List<Bitmap> = RESOURCE_NAMES.mapNotNull { name ->
            runCatching {
                val id = resources.getIdentifier(name, "drawable", QQPlayerProfile.packageName)
                if (id == 0) null else BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }.onFailure { moduleInfo("QQ artwork placeholder unavailable ($name): ${it.javaClass.simpleName}") }.getOrNull()
        }
    }
}
