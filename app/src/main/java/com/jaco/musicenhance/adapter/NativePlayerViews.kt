package com.jaco.musicenhance.adapter

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.jaco.musicenhance.player.PLAYER_OVERLAY_TAG
import kotlin.math.max
import kotlin.math.min

internal object NativePlayerViews {
    /** Prefer the full-size image the host app has already loaded for its native player. */
    fun findArtwork(root: ViewGroup): Bitmap? {
        var best: Bitmap? = null
        var bestScore = 0

        fun visit(view: View) {
            if (view.tag == PLAYER_OVERLAY_TAG) return
            // Background images may be large square bitmaps that the host has already blurred.
            if (view is ImageView && view.width > 0 && view.height > 0) {
                val name = resourceName(view).lowercase()
                if (name.contains("blur") || name.contains("background") ||
                    name.endsWith("_bg") || name.startsWith("bg_")) return
                val bitmap = (view.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    val sourceShort = min(bitmap.width, bitmap.height)
                    val sourceLong = max(bitmap.width, bitmap.height)
                    val viewShort = min(view.width, view.height)
                    val viewLong = max(view.width, view.height)
                    val sourceSquare = sourceShort.toFloat() / sourceLong.coerceAtLeast(1)
                    val viewSquare = viewShort.toFloat() / viewLong.coerceAtLeast(1)
                    if (sourceShort >= 150 && viewShort >= 180 && sourceSquare >= 0.86f && viewSquare >= 0.72f) {
                        val score = sourceShort * 3 + viewShort
                        if (score > bestScore) {
                            best = bitmap
                            bestScore = score
                        }
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }

        visit(root)
        return best
    }

    fun find(root: View, predicate: (View) -> Boolean): View? {
        if (root.tag == PLAYER_OVERLAY_TAG) return null
        if (predicate(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            find(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    fun resourceName(view: View): String = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    fun clickControl(root: ViewGroup, keywords: List<String>): Boolean {
        val match = findNativeControl(root, keywords) ?: return false
        return match.performClick() || match.callOnClick()
    }

    private fun findNativeControl(root: ViewGroup, keywords: List<String>): View? {
        var best: View? = null
        var bestScore = 0

        fun visit(view: View) {
            if (view.tag == PLAYER_OVERLAY_TAG) return
            val description = view.contentDescription?.toString().orEmpty()
            val text = (view as? TextView)?.text?.toString().orEmpty()
            val resourceName = if (view.id != View.NO_ID) {
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            } else {
                ""
            }
            val fields = listOf(description, text, resourceName)
            val matches = fields.count { value -> keywords.any { value.contains(it, ignoreCase = true) } }
            if (matches > 0) {
                var target: View? = view
                var depth = 0
                while (target != null && !target.isClickable && depth < 4) {
                    target = target?.parent as? View
                    depth++
                }
                val clickable = target?.takeIf { it.isClickable && it.isEnabled && it.visibility == View.VISIBLE }
                val score = matches * 100 + (if (description.isNotBlank()) 30 else 0) + (if (resourceName.isNotBlank()) 20 else 0)
                if (clickable != null && score > bestScore) {
                    best = clickable
                    bestScore = score
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }

        visit(root)
        return best
    }

}
