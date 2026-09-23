package com.jaco.musicenhance.adapter.qq

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.LruCache
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Full resolution artwork stays in the UI process, avoiding MediaSession/Binder thumbnails. */
internal class QQAlbumArtwork(private val classLoader: ClassLoader) {
    private data class Result(val songKey: String, val bitmap: Bitmap)
    @Volatile private var result: Result? = null
    @Volatile private var wantedKey = ""
    private val loading = AtomicBoolean()
    private var lastAttemptKey = ""
    @Volatile private var retryAt = 0L
    private val api by lazy { Api(classLoader) }

    // Called on the UI thread. Network, reflection and image decoding all run on artworkWorker.
    fun snapshot(snapshot: PlayerSnapshot): Bitmap? {
        val key = "${snapshot.title}\u0000${snapshot.artist}\u0000${snapshot.album}"
        wantedKey = key
        result?.takeIf { it.songKey == key }?.let { return it.bitmap }
        val now = SystemClock.elapsedRealtime()
        if (snapshot.durationMs <= 0 || loading.get()) return null
        if (lastAttemptKey == key && now < retryAt) return null
        if (!loading.compareAndSet(false, true)) return null
        lastAttemptKey = key
        retryAt = now + 30_000L
        artworkWorker.post {
            try {
                val playEnvironment = api.getPlayEnvironment.invoke(null)
                val song = api.getPlaySong.invoke(playEnvironment)
                if (song == null || api.songTitle.invoke(song) != snapshot.title) {
                    retryAt = SystemClock.elapsedRealtime() + 500L
                    return@post
                }
                val songId = api.songId.invoke(song)
                var best: Bitmap? = null
                // QQ's PhotoDomainUrlBuilder indexes: 4=1500, 3=1200, 5=800.
                for ((sizeIndex, expectedSize) in listOf(4 to 1500, 3 to 1200, 5 to 800)) {
                    if (wantedKey != key) return@post
                    val address = api.coverUrl.invoke(null, song, sizeIndex) as? String ?: continue
                    if (address.isBlank()) continue
                    val bitmap = cache.get(address) ?: runCatching { download(address) }
                        .onFailure { moduleInfo("QQ HD artwork request failed: size=$expectedSize, ${it.javaClass.simpleName}") }
                        .getOrNull()?.also { cache.put(address, it) } ?: continue
                    if (min(bitmap.width, bitmap.height) > min(best?.width ?: 0, best?.height ?: 0)) best = bitmap
                    moduleInfo("QQ HD artwork: requested=$expectedSize, actual=${bitmap.width}x${bitmap.height}, songId=$songId")
                    if (min(bitmap.width, bitmap.height) >= expectedSize) break
                }
                val currentSong = api.getPlaySong.invoke(playEnvironment)
                if (wantedKey == key && currentSong != null && api.songId.invoke(currentSong) == songId) {
                    best?.let { result = Result(key, it) }
                } else {
                    retryAt = SystemClock.elapsedRealtime() + 500L
                }
            } catch (error: Throwable) {
                val cause = error.cause ?: error
                moduleInfo("QQ HD artwork unavailable: ${cause.javaClass.simpleName}: ${cause.message}")
            } finally {
                loading.set(false)
            }
        }
        return null
    }

    private class Api(classLoader: ClassLoader) {
        private val song = Class.forName("com.tencent.qqmusicplayerprocess.songinfo.SongInfo", false, classLoader)
        val getPlayEnvironment = Class.forName("com.tencent.qqmusic.common.ipc.MusicProcess", false, classLoader).getMethod("playEnv")
        val getPlaySong = Class.forName("com.tencent.qqmusic.common.ipc.IPlayProcessMethods", false, classLoader).getMethod("getPlaySong")
        val songTitle = song.getMethod("j3")
        val songId = song.getMethod("C3")
        val coverUrl = Class.forName("com.tencent.qqmusiccommon.appconfig.albumpic.b", false, classLoader)
            .getMethod("e", song, Int::class.javaPrimitiveType)
    }

    companion object {
        private val artworkWorker = Handler(HandlerThread("MusicEnhance-artwork").apply { start() }.looper)
        private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private const val MAX_BYTES = 8 * 1024 * 1024

        private fun download(address: String): Bitmap? {
            var url = URL(address)
            repeat(4) {
                if (url.protocol != "https") return null
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = false
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: return null
                        url = URL(url, location)
                    } else {
                        if (code != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_BYTES) return null
                        val bytes = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > MAX_BYTES) return null
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        if (min(options.outWidth, options.outHeight) < 400 ||
                            min(options.outWidth, options.outHeight).toFloat() / max(options.outWidth, options.outHeight) < 0.72f
                        ) return null
                        var sample = 1
                        while (max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inScaled = false
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })
                    }
                } finally {
                    connection.disconnect()
                }
            }
            return null
        }
    }
}
