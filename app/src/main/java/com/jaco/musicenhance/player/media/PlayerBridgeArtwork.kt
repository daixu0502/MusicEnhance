package com.jaco.musicenhance.player.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import androidx.core.graphics.scale
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Same-UID processes exchange a small filename; image bytes never enter a broadcast parcel. */
internal class PlayerBridgeArtwork(private val directory: File) {
    private var cachedTrackKey: String? = null
    private var cachedGeneration = 0
    private var cachedFile: File? = null

    // Called on the bridge worker, never from a metadata callback or the rendering thread.
    fun write(trackKey: String, bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled) return null
        if (cachedTrackKey == trackKey && cachedGeneration == bitmap.generationId) {
            cachedFile?.takeIf(File::isFile)?.let { return it.name }
        }
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val file = File(directory, "${Process.myPid()}_${UUID.randomUUID()}.jpg")
        val temporary = File(directory, "${file.name}.tmp")
        var scaled: Bitmap? = null
        try {
            val largest = max(bitmap.width, bitmap.height)
            val output = if (largest > MAX_SIDE) {
                val ratio = MAX_SIDE.toFloat() / largest
                bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap
            scaled = output
            var compressed = temporary.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (compressed && temporary.length() > MAX_BYTES) {
                val ratio = 640f / max(output.width, output.height)
                val fallback = output.scale((output.width * ratio).toInt().coerceAtLeast(1),
                    (output.height * ratio).toInt().coerceAtLeast(1), true)
                try {
                    compressed = temporary.outputStream().use { fallback.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                } finally {
                    if (fallback !== output) fallback.recycle()
                }
            }
            if (!compressed || temporary.length() !in 1..MAX_BYTES || !temporary.renameTo(file)) return null
            cachedTrackKey = trackKey
            cachedGeneration = bitmap.generationId
            cachedFile = file
            prune(file)
            return file.name
        } finally {
            temporary.delete()
            if (scaled !== bitmap) scaled?.recycle()
        }
    }

    fun read(name: String?): Bitmap? {
        if (name == null || !FILE_NAME.matches(name)) return null
        val file = File(directory, name)
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..MAX_SIDE || bounds.outHeight !in 1..MAX_SIDE) return null
        return BitmapFactory.decodeFile(file.path)
    }

    private fun prune(current: File) {
        directory.listFiles { file -> FILE_NAME.matches(file.name) && file != current }
            ?.sortedByDescending(File::lastModified)?.drop(MAX_FILES - 1)?.forEach(File::delete)
    }

    private companion object {
        const val MAX_SIDE = 1_280
        const val MAX_BYTES = 800_000L
        const val MAX_FILES = 8
        val FILE_NAME = Regex("[0-9]+_[0-9a-f-]{36}\\.jpg")
    }
}
