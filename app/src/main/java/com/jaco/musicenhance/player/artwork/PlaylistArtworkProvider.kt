package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Current-song loading never waits for neighbour prefetch. No host reflection runs in snapshot(). */
internal class PlaylistArtworkProvider<S>(
    private val sourceName: String,
    source: () -> PlaylistArtworkSource<S>,
    private val diskCache: ArtworkDiskCache? = null,
    private val worker: Handler = currentWorker,
    private val prefetchWorker: Handler = queueWorker,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ArtworkProvider {
    // Native/media images are available before this optional quality upgrade completes.
    override val holdPreviousArtworkWhileLoading = false
    private class Request(val player: PlayerSnapshot) {
        var bitmap: Bitmap? = null
        var songKey: String? = null
        var checkAtMs = 0L
        var retryAtMs = 0L
        var prefetchedKey: String? = null
        var prefetchRetryAtMs = 0L
    }

    private val api by lazy(source)
    @Volatile private var currentRequest: Request? = null
    private var loadPending = false
    private var queuedPrefetch: Runnable? = null

    override fun snapshot(player: PlayerSnapshot): Bitmap? {
        val request = currentRequest?.takeIf { it.player.metadataKey == player.metadataKey }
            ?: Request(player).also {
                cancelPrefetch()
                currentRequest = it
            }
        val nowMs = SystemClock.elapsedRealtime()
        if (player.durationMs > 0 && !loadPending && nowMs >= request.checkAtMs) {
            request.checkAtMs = nowMs + IDENTITY_CHECK_MS
            load(request, nowMs)
        }
        return request.bitmap?.takeUnless { it.isRecycled }
    }

    private fun load(request: Request, nowMs: Long) {
        loadPending = true
        val previousKey = request.songKey
        val previousBitmap = request.bitmap?.takeUnless { it.isRecycled }
        val retryAtMs = request.retryAtMs
        worker.post {
            val active = { currentRequest === request }
            var song: S? = null
            var songKey: String? = null
            var synchronizedSong = false
            val result = runCatching {
                if (!active()) return@runCatching null
                val current = api.currentSong(request.player) ?: return@runCatching null
                song = current
                songKey = api.cacheKey(current)
                val bitmap = if (songKey == previousKey && previousBitmap != null) previousBitmap
                    else if (songKey == previousKey && nowMs < retryAtMs) null
                    else loadSong(current, active, prefetch = false)
                synchronizedSong = active() && api.isCurrentSong(current)
                bitmap.takeIf { synchronizedSong }
            }
            mainHandler.post publish@{
                loadPending = false
                if (!active()) return@publish
                result.onFailure {
                    request.checkAtMs = SystemClock.elapsedRealtime() + RETRY_MS
                    moduleInfo("$sourceName artwork unavailable: ${(it.cause ?: it).javaClass.simpleName}")
                }
                if (!synchronizedSong) return@publish
                val bitmap = result.getOrNull()
                if (request.songKey != songKey) {
                    cancelPrefetch()
                    request.prefetchedKey = null
                    request.prefetchRetryAtMs = 0
                    request.retryAtMs = 0
                }
                request.songKey = songKey
                request.bitmap = bitmap
                if (bitmap == null && nowMs >= request.retryAtMs) request.retryAtMs = nowMs + RETRY_MS
                song?.let { prefetch(request, it) }
            }
        }
    }

    private fun loadSong(song: S, active: () -> Boolean, prefetch: Boolean): Bitmap? {
        if (!active()) return null
        val key = api.cacheKey(song)
        fun download(address: String): Bitmap? {
            val startedAtMs = SystemClock.elapsedRealtime()
            val result = runCatching {
                ArtworkLoader.load(address, active, diskCache, keepInMemory = !prefetch || diskCache == null)
            }
            if (active() && result.getOrNull() == null) {
                val failure = result.exceptionOrNull()?.let { (it.cause ?: it).javaClass.simpleName } ?: "no-image"
                moduleInfo("$sourceName artwork download: song=$key, prefetch=$prefetch, elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}, failure=$failure")
            }
            return result.getOrNull()
        }
        val seen = hashSetOf<String>()
        resolvedAddresses.get(key)?.let { address ->
            seen += address
            download(address)?.let { return it }
            if (!active()) return null
            resolvedAddresses.remove(key)
        }
        for (address in api.addresses(song, active)) {
            if (!active()) return null
            if (!seen.add(address)) continue
            val bitmap = download(address) ?: continue
            if (!active()) return null
            resolvedAddresses.put(key, address)
            moduleInfo("$sourceName artwork: prefetch=$prefetch, actual=${bitmap.width}x${bitmap.height}, song=$key")
            return bitmap
        }
        return null
    }

    private fun prefetch(request: Request, centre: S) {
        val key = request.songKey ?: return
        if (request.prefetchedKey == key || SystemClock.elapsedRealtime() < request.prefetchRetryAtMs) return
        request.prefetchedKey = key
        val task = Runnable {
            val active = { currentRequest === request }
            var retryDelayMs = 0L
            runCatching {
                if (!active() || !api.isCurrentSong(centre)) return@runCatching
                val neighbours = api.neighbours(centre).distinctBy(api::cacheKey).take(MAX_NEIGHBOURS)
                moduleInfo("$sourceName artwork prefetch: centre=$key, neighbours=${neighbours.map(api::cacheKey)}")
                // Playback metadata can arrive before the host finishes publishing its queue.
                if (neighbours.isEmpty()) retryDelayMs = QUEUE_SYNC_RETRY_MS
                for (song in neighbours) {
                    if (!active() || !api.isCurrentSong(centre)) break
                    val address = resolvedAddresses.get(api.cacheKey(song))
                    if (address != null && (ArtworkLoader.cached(address) != null || diskCache?.contains(address) == true)) continue
                    loadSong(song, { active() && api.isCurrentSong(centre) }, prefetch = true)
                }
            }.onFailure {
                retryDelayMs = RETRY_MS
                if (active()) moduleInfo("$sourceName artwork prefetch unavailable: ${(it.cause ?: it).javaClass.simpleName}")
            }
            if (retryDelayMs > 0) mainHandler.post {
                if (active() && request.songKey == key) {
                    request.prefetchedKey = null
                    request.prefetchRetryAtMs = SystemClock.elapsedRealtime() + retryDelayMs
                }
            }
        }
        queuedPrefetch = task
        prefetchWorker.post(task)
    }

    private fun cancelPrefetch() {
        queuedPrefetch?.let(prefetchWorker::removeCallbacks)
        queuedPrefetch = null
    }

    override fun release() {
        currentRequest = null
        cancelPrefetch()
        // Running work checks request identity; its completion releases loadPending for reattachment.
    }

    private companion object {
        const val IDENTITY_CHECK_MS = 500L
        const val RETRY_MS = 30_000L
        const val QUEUE_SYNC_RETRY_MS = 5_000L
        const val MAX_NEIGHBOURS = 6
        val resolvedAddresses = LruCache<String, String>(128)
        val currentWorker = Handler(HandlerThread("MusicEnhance-cover").apply { start() }.looper)
        val queueWorker = Handler(HandlerThread("MusicEnhance-cover-queue", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }
}
