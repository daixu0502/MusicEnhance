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

/** Native preview, cache/download upgrades and neighbour prefetch share one per-session pipeline.
 * Host IPC, address lookup, disk reads and decoding stay on workers; views only supply ready bitmaps.
 */
internal class PlaylistArtworkProvider<S>(
    private val sourceName: String,
    source: () -> PlaylistArtworkSource<S>,
    private val diskCache: ArtworkDiskCache? = null,
    private val worker: Handler = currentWorker,
    private val prefetchWorker: Handler = queueWorker,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** UI-thread getter for the host's already decoded cover. Never decode or perform IPC here. */
    private val readNativeArtwork: () -> Bitmap? = { null },
    private val loadArtwork: (String, () -> Boolean, ArtworkDiskCache?, Boolean) -> Bitmap? = ArtworkLoader::load,
    /** Optional host placeholder check. Runs off the UI thread, before any native image is published. */
    private val acceptNativeArtwork: ((Bitmap) -> Boolean)? = null,
    private val nativeWorker: Handler = previewWorker,
) : ArtworkProvider {
    override val ownsArtworkSelection = true
    // Native/media images are available before this optional quality upgrade completes.
    override val holdPreviousArtworkWhileLoading = false
    private class Request(var player: PlayerSnapshot) {
        var bitmap: Bitmap? = null
        var nativeBitmap: Bitmap? = null
        var rejectedNative: Bitmap? = null
        var nativeReadAtMs = 0L
        var nativeGeneration = 0L
        var songKey: String? = null
        var checkAtMs = 0L
        var retryAtMs = 0L
        var prefetchedKey: String? = null
        var prefetchRetryAtMs = 0L

        fun matchesMetadata(other: PlayerSnapshot): Boolean = player.title == other.title &&
            player.artist == other.artist &&
            (player.album.isBlank() || other.album.isBlank() || player.album == other.album)
    }

    private val api by lazy(source)
    @Volatile private var currentRequest: Request? = null
    private var loadPending = false
    private var nativeCheckPending = false
    private var queuedPrefetch: Runnable? = null

    override fun snapshot(player: PlayerSnapshot): Bitmap? {
        val request = currentRequest?.takeIf { it.matchesMetadata(player) }
            ?: Request(player).also {
                it.rejectedNative = currentRequest?.nativeBitmap
                cancelPrefetch()
                currentRequest = it
            }
        // QQ can publish title/artwork before album metadata; this is not a new song.
        // Keep the known album across omissions so an actual album change still resets the request.
        request.player = if (player.album.isBlank() && request.player.album.isNotBlank()) {
            player.copy(album = request.player.album)
        } else player
        val nowMs = SystemClock.elapsedRealtime()
        // Show native artwork immediately while cache lookup/network work runs independently.
        if (request.bitmap == null && !nativeCheckPending && nowMs >= request.nativeReadAtMs) {
            request.nativeReadAtMs = nowMs + NATIVE_POLL_MS
            val native = runCatching(readNativeArtwork).onFailure {
                moduleInfo("$sourceName native artwork unavailable: ${it.javaClass.simpleName}")
            }.getOrNull()
            val candidates = listOfNotNull(player.artwork, native).distinct().filter {
                !it.isRecycled && it !== request.rejectedNative && ArtworkDimensions.isUsable(it.width, it.height)
            }
            updateNativeArtwork(request, candidates)
        }
        if (player.durationMs > 0 && !loadPending && nowMs >= request.checkAtMs) {
            request.checkAtMs = nowMs + IDENTITY_CHECK_MS
            load(request, nowMs)
        }
        return request.bitmap?.takeUnless { it.isRecycled } ?: request.nativeBitmap?.takeUnless { it.isRecycled }
    }

    private fun updateNativeArtwork(request: Request, candidates: List<Bitmap>) {
        val check = acceptNativeArtwork
        if (check == null) {
            candidates.maxByOrNull { minOf(it.width, it.height) }?.let { request.nativeBitmap = it }
            return
        }
        if (candidates.isEmpty()) return
        // A dedicated worker prevents network requests/prefetch from delaying native previews.
        val versions = candidates.map { it to it.generationId }
        val nativeGeneration = request.nativeGeneration
        nativeCheckPending = true
        nativeWorker.post {
            val accepted = if (currentRequest !== request) emptyList() else versions.filter { (bitmap, generation) ->
                !bitmap.isRecycled && bitmap.generationId == generation && runCatching { check(bitmap) }
                    .onFailure { moduleInfo("$sourceName native artwork check failed: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
            }
            mainHandler.post {
                nativeCheckPending = false
                if (currentRequest === request && request.nativeGeneration == nativeGeneration && request.bitmap == null) {
                    accepted.filter { (bitmap, generation) ->
                        !bitmap.isRecycled && bitmap.generationId == generation && bitmap !== request.rejectedNative
                    }.maxByOrNull { (bitmap, _) -> minOf(bitmap.width, bitmap.height) }
                        ?.let {
                            if (request.nativeBitmap == null) {
                                moduleInfo("$sourceName artwork preview: actual=${it.first.width}x${it.first.height}, song=${request.songKey ?: "pending"}")
                            }
                            request.nativeBitmap = it.first
                        }
                }
            }
        }
    }

    private fun load(request: Request, nowMs: Long) {
        loadPending = true
        val previousKey = request.songKey
        val previousBitmap = request.bitmap?.takeUnless { it.isRecycled }
        val retryAtMs = request.retryAtMs
        val player = request.player
        worker.post {
            val active = { currentRequest === request }
            var song: S? = null
            var songKey: String? = null
            var synchronizedSong = false
            val result = runCatching {
                if (!active()) return@runCatching null
                val current = api.currentSong(player) ?: return@runCatching null
                song = current
                songKey = api.cacheKey(current)
                if (previousKey != null && previousKey != songKey) mainHandler.post {
                    if (active()) {
                        request.bitmap = null
                        request.nativeGeneration++
                        request.rejectedNative = request.nativeBitmap
                        request.nativeBitmap = null
                        request.nativeReadAtMs = 0
                    }
                }
                val bitmap = if (songKey == previousKey && previousBitmap != null) previousBitmap
                    else if (songKey == previousKey && nowMs < retryAtMs) null
                    else loadSong(current, active, prefetch = false) { api.isCurrentSong(current) }
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

    private fun loadSong(song: S, active: () -> Boolean, prefetch: Boolean, matchesSelection: () -> Boolean): Bitmap? {
        // Native identity checks run between attempts, never once per network buffer.
        val valid = { active() && matchesSelection() }
        if (!valid()) return null
        val key = api.cacheKey(song)
        fun download(address: String): Bitmap? {
            if (!valid()) return null
            val startedAtMs = SystemClock.elapsedRealtime()
            val result = runCatching {
                loadArtwork(address, active, diskCache, !prefetch || diskCache == null)
            }
            if (active() && result.getOrNull() == null) {
                val failure = result.exceptionOrNull()?.let { (it.cause ?: it).javaClass.simpleName } ?: "no-image"
                moduleInfo("$sourceName artwork download: song=$key, prefetch=$prefetch, elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}, failure=$failure")
            }
            return result.getOrNull().takeIf { valid() }
        }
        val seen = hashSetOf<String>()
        (resolvedAddresses.get(key) ?: diskCache?.readSongAddress(key))?.let { address ->
            ArtworkLoader.loadCached(address, active, diskCache, keepInMemory = !prefetch || diskCache == null)?.let {
                if (!valid()) return null
                resolvedAddresses.put(key, address)
                if (!prefetch) moduleInfo("$sourceName artwork cache hit: actual=${it.width}x${it.height}, song=$key")
                return it
            }
            if (!active()) return null
            resolvedAddresses.remove(key)
            diskCache?.removeSongAddress(key)
        }
        for (address in api.addresses(song, valid)) {
            if (!valid()) return null
            if (!seen.add(address)) continue
            val bitmap = download(address) ?: continue
            if (!active()) return null
            resolvedAddresses.put(key, address)
            diskCache?.writeSongAddress(key, address)
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
                val neighbours = api.neighbours(centre).filter { api.cacheKey(it) != key }.distinctBy(api::cacheKey).take(MAX_NEIGHBOURS)
                moduleInfo("$sourceName artwork prefetch: centre=$key, neighbours=${neighbours.map(api::cacheKey)}")
                // Playback metadata can arrive before the host finishes publishing its queue.
                if (neighbours.isEmpty()) retryDelayMs = QUEUE_SYNC_RETRY_MS
                for (song in neighbours) {
                    if (!active() || !api.isCurrentSong(centre)) break
                    val address = resolvedAddresses.get(api.cacheKey(song)) ?: diskCache?.readSongAddress(api.cacheKey(song))
                    if (address != null && (ArtworkLoader.cached(address) != null || diskCache?.contains(address) == true)) continue
                    loadSong(song, active, prefetch = true) { api.isCurrentSong(centre) }
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
        const val NATIVE_POLL_MS = 250L
        val resolvedAddresses = LruCache<String, String>(128)
        val currentWorker = Handler(HandlerThread("MusicEnhance-cover").apply { start() }.looper)
        val queueWorker = Handler(HandlerThread("MusicEnhance-cover-queue", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
        val previewWorker by lazy { Handler(HandlerThread("MusicEnhance-cover-preview").apply { start() }.looper) }
    }
}
