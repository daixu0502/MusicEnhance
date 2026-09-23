package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Shared URL cache and bounded HTTPS loading. load() must run off the UI thread. */
internal object ArtworkLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(address: String): Bitmap? = cache.get(address)?.takeUnless { it.isRecycled }

    fun load(
        address: String,
        isCurrent: () -> Boolean,
        diskCache: ArtworkDiskCache? = null,
        keepInMemory: Boolean = true,
    ): Bitmap? {
        if (!isCurrent()) return null
        cached(address)?.let { return it }
        val storedBytes = diskCache?.read(address)
        var bitmap = storedBytes?.let(::decode)
        if (!isCurrent()) return null
        if (storedBytes != null && bitmap == null) diskCache.remove(address)
        if (bitmap == null) {
            val bytes = download(address, isCurrent) ?: return null
            if (!isCurrent()) return null
            bitmap = decode(bytes) ?: return null
            if (!isCurrent()) return null
            diskCache?.write(address, bytes)
        }
        if (!isCurrent()) return null
        if (keepInMemory) cache.put(address, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (!ArtworkDimensions.isUsable(options.outWidth, options.outHeight)) return null
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun download(address: String, isCurrent: () -> Boolean): ByteArray? {
        var url = URL(address)
        repeat(4) {
            if (!isCurrent()) return null
            if (url.protocol != "https") return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (!isCurrent()) return null
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    url = URL(url, location)
                } else {
                    if (code != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_BYTES) return null
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (!isCurrent()) return null
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > MAX_BYTES) return null
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    if (!isCurrent()) return null
                    return bytes
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}
