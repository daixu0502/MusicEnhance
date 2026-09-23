package com.jaco.musicenhance.adapter.qq

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.artwork.ArtworkLoader
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.ArtworkProvider
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Owns QQ artwork requests. Reflection, source fallback and image loading are separate. */
internal class QQArtworkProvider(
    classLoader: ClassLoader,
    private val worker: Handler = artworkWorker,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val diskCache: ArtworkDiskCache? = null,
    private val prefetchWorker: Handler = playlistWorker,
    source: () -> QQArtworkSource = { QQArtworkApi(classLoader) },
) : ArtworkProvider {
    private class Request(val metadataKey: String) {
        var bitmap: Bitmap? = null
        var retryAtMs = 0L
        var prefetchScheduled = false
        var prefetchRetryAtMs = 0L
    }

    private val api by lazy(source)
    // Only identity is read by the worker; all request fields and publication stay on the UI thread.
    @Volatile private var currentRequest: Request? = null
    private var loadPending = false
    private var queuedPrefetch: Runnable? = null

    private data class LoadedArtwork(val address: String, val bitmap: Bitmap)

    override fun snapshot(player: PlayerSnapshot): Bitmap? {
        val request = currentRequest?.takeIf { it.metadataKey == player.metadataKey }
            ?: Request(player.metadataKey).also {
                cancelQueuedPrefetch()
                currentRequest = it
            }
        request.bitmap?.takeUnless { it.isRecycled }?.let {
            ensurePrefetch(request, player.title)
            return it
        }
        resolvedAddresses.get(request.metadataKey)?.let { address ->
            ArtworkLoader.cached(address)?.let {
                request.bitmap = it
                ensurePrefetch(request, player.title)
                return it
            }
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (player.durationMs > 0 && !loadPending && nowMs >= request.retryAtMs) {
            loadArtwork(request, player.title, nowMs)
        }
        return null
    }

    private fun loadArtwork(request: Request, title: String, nowMs: Long) {
        loadPending = true
        request.retryAtMs = nowMs + RETRY_INTERVAL_MS
        worker.post {
            val isCurrent = { currentRequest === request }
            var retrySoon = false
            val result = runCatching {
                if (!isCurrent()) return@runCatching null
                val song = api.currentSong(title)
                if (song == null) {
                    retrySoon = true
                    return@runCatching null
                }
                val artwork = loadSongArtwork(song, isCurrent, prefetch = false)
                if (!isCurrent()) return@runCatching null
                if (api.isCurrentSong(song)) artwork else {
                    retrySoon = true
                    null
                }
            }
            mainHandler.post publish@{
                loadPending = false
                // Identity also rejects an old callback after release/reopening the same song.
                if (!isCurrent()) return@publish
                if (retrySoon) request.retryAtMs = SystemClock.elapsedRealtime() + SONG_SYNC_RETRY_MS
                result.onSuccess { artwork ->
                    if (artwork != null) {
                        resolvedAddresses.put(request.metadataKey, artwork.address)
                        request.bitmap = artwork.bitmap
                    }
                    if (!retrySoon) ensurePrefetch(request, title)
                }.onFailure { error ->
                    val cause = error.cause ?: error
                    moduleInfo("QQ artwork unavailable: ${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
        }
    }

    /** Used by both current-song loading and prefetch: identical quality/placeholder fallback. */
    private fun loadSongArtwork(
        song: QQArtworkSource.Song,
        isCurrent: () -> Boolean,
        prefetch: Boolean,
    ): LoadedArtwork? {
        fun download(address: String) = ArtworkLoader.load(
            address, isCurrent, diskCache, keepInMemory = !prefetch || diskCache == null,
        )
        if (song.id > 0) songAddresses.get(song.id)?.let { address ->
            runCatching { download(address) }.getOrNull()?.let { return LoadedArtwork(address, it) }
            if (!isCurrent()) return null
            songAddresses.remove(song.id)
        }
        var resolvedAddress: String? = null
        val bitmap = QQArtworkFallback.loadAlbumOrSinger(
            albumAddress = { size -> api.coverAddress(song, size) },
            singerAddress = { size -> api.singerAddress(song, size) },
            download = { address -> download(address)?.also { resolvedAddress = address } },
            isCurrent = isCurrent,
            onAttempt = { source, size, image, error ->
                val outcome = image?.let { "actual=${it.width}x${it.height}" }
                    ?: "unavailable=${(error?.cause ?: error)?.javaClass?.simpleName ?: "no-image"}"
                moduleInfo("QQ artwork: prefetch=$prefetch, source=$source, requested=${size.pixels}, $outcome, songId=${song.id}")
            },
        ) ?: return null
        val address = resolvedAddress ?: return null
        if (!isCurrent()) return null
        if (song.id > 0) songAddresses.put(song.id, address)
        return LoadedArtwork(address, bitmap)
    }

    /** A separate worker keeps an old/slow neighbour download from delaying the current cover. */
    private fun ensurePrefetch(request: Request, title: String) {
        if (request.prefetchScheduled || SystemClock.elapsedRealtime() < request.prefetchRetryAtMs) return
        request.prefetchScheduled = true
        val task = Runnable {
            val isCurrent = { currentRequest === request }
            runCatching {
                if (!isCurrent()) return@runCatching
                val centre = api.currentSong(title)
                if (centre == null) {
                    mainHandler.post {
                        if (isCurrent()) {
                            request.prefetchScheduled = false
                            request.prefetchRetryAtMs = SystemClock.elapsedRealtime() + SONG_SYNC_RETRY_MS
                        }
                    }
                    return@runCatching
                }
                val neighbours = api.neighbours(centre)
                moduleInfo("QQ artwork prefetch: centre=${centre.id}, neighbours=${neighbours.map { it.id }}")
                for (song in neighbours) {
                    if (!isCurrent() || !api.isCurrentSong(centre)) break
                    val address = songAddresses.get(song.id)
                    if (address != null && diskCache?.contains(address) == true) continue
                    loadSongArtwork(song, isCurrent, prefetch = true)
                }
            }.onFailure { error ->
                if (isCurrent()) moduleInfo("QQ artwork prefetch unavailable: ${(error.cause ?: error).javaClass.simpleName}")
            }
        }
        queuedPrefetch = task
        prefetchWorker.post(task)
    }

    private fun cancelQueuedPrefetch() {
        queuedPrefetch?.let(prefetchWorker::removeCallbacks)
        queuedPrefetch = null
    }

    override fun release() {
        currentRequest = null
        cancelQueuedPrefetch()
        // Leave the worker busy until completion; a reattached view cannot start duplicate work.
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 30_000L
        const val SONG_SYNC_RETRY_MS = 500L
        val artworkWorker = Handler(HandlerThread("MusicEnhance-artwork").apply { start() }.looper)
        val playlistWorker = Handler(HandlerThread("MusicEnhance-artwork-prefetch", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
        // QQ metadata aliases are kept within this adapter; generic bitmaps are keyed by URL.
        val resolvedAddresses = LruCache<String, String>(128)
        val songAddresses = LruCache<Long, String>(128)
    }
}
