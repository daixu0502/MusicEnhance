package com.jaco.musicenhance.adapter.apple

import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Apple Music 6.5.2. Read the process browser on its UI thread, not a stopped player Fragment. */
internal class ApplePlaybackSource(private val loader: ClassLoader) {
    private val browserField by lazy { loader.loadClass("c9.a").getField("f") }
    private val controllerType by lazy { loader.loadClass("E3.F") }
    private val metadataMethod by lazy { controllerType.getMethod("m0") }
    private val itemMethod by lazy {
        loader.loadClass("com.apple.android.music.player.O").getMethod("b", loader.loadClass("v3.v"))
    }
    private val favoriteSource = lazy { AppleFavoriteSource(loader) }
    private val favorites by favoriteSource
    private val timelineType by lazy { loader.loadClass("v3.J") }
    private val windowType by lazy { loader.loadClass("v3.J\$d") }
    private var lastMetadata: Any? = null
    private var lastItem: Any? = null
    private var nextQueueReadAtMs = 0L
    private var lastQueueFailure: String? = null
    @Volatile var artwork: AppleArtworkSource.Playlist? = null
        private set

    fun browser(): Any? = browserField.get(null)

    fun item(player: PlayerSnapshot): Any? {
        val browser = browser() ?: return null.also { artwork = null }
        val metadata = metadataMethod.invoke(browser) ?: return null.also { artwork = null }
        if (metadata !== lastMetadata) {
            lastItem = itemMethod.invoke(null, metadata)
            lastMetadata = metadata
        }
        val item = lastItem?.takeIf { matches(it, player) }
        refreshArtwork(browser, item)
        return item
    }

    fun repeatMode(): Int? = browser()?.let { controllerType.getMethod("getRepeatMode").invoke(it) as? Int }
    fun shuffleEnabled(): Boolean = browser()?.let {
        controllerType.getMethod("getShuffleModeEnabled").invoke(it) == true
    } ?: false

    fun canSetRepeat(): Boolean = browser()?.let {
        // Media3 COMMAND_SET_REPEAT_MODE = 15; disconnected browsers have no available commands.
        controllerType.getMethod("d", Int::class.javaPrimitiveType).invoke(it, 15) == true
    } ?: false

    fun setRepeatMode(mode: Int, shuffle: Boolean = false): Boolean {
        val browser = browser() ?: return false
        if (!canSetRepeat()) return false
        val changeShuffle = shuffleEnabled() != shuffle
        if (changeShuffle) {
            if (controllerType.getMethod("d", Int::class.javaPrimitiveType).invoke(browser, 14) != true) return false
        }
        controllerType.getMethod("setRepeatMode", Int::class.javaPrimitiveType).invoke(browser, mode)
        if (changeShuffle) controllerType.getMethod("setShuffleModeEnabled", Boolean::class.javaPrimitiveType).invoke(browser, shuffle)
        return true
    }

    fun favoriteState(item: Any?): Boolean? = favorites.state(item)
    val favoritePending: Boolean get() = favoriteSource.isInitialized() && favorites.isPending

    fun toggleFavorite(item: Any, previous: Boolean): Boolean =
        item === lastItem && favorites.toggle(item, previous)

    fun release() {
        if (favoriteSource.isInitialized()) favorites.release()
        artwork = null
        lastItem = null
        lastMetadata = null
    }

    private fun matches(item: Any, player: PlayerSnapshot): Boolean =
        text(item, "getTitle") == player.title.trim() &&
            matchesOptional(text(item, "getArtistName"), player.artist) &&
            matchesOptional(text(item, "getCollectionName"), player.album)

    private fun refreshArtwork(browser: Any, item: Any?) {
        val song = item?.let(::song)
        if (song == null) { artwork = null; return }
        val nowMs = SystemClock.elapsedRealtime()
        val previous = artwork
        if (previous?.current == song && nowMs < nextQueueReadAtMs) return
        // Publish the new identity first; a failed queue read must never retain the old cover.
        artwork = AppleArtworkSource.Playlist(song, emptyList())
        nextQueueReadAtMs = nowMs + QUEUE_REFRESH_MS
        val neighbours = runCatching { readNeighbours(browser, song) }
            .onSuccess { lastQueueFailure = null }
            .onFailure {
                val failure = (it.cause ?: it).toString()
                if (failure != lastQueueFailure) moduleInfo("Apple Music artwork queue unavailable: $failure")
                lastQueueFailure = failure
            }.getOrDefault(emptyList())
        artwork = AppleArtworkSource.Playlist(song, neighbours)
    }

    private fun readNeighbours(browser: Any, current: AppleArtworkSource.Song): List<AppleArtworkSource.Song> {
        val timeline = controllerType.getMethod("getCurrentTimeline").invoke(browser) ?: return emptyList()
        val index = controllerType.getMethod("e0").invoke(browser) as Int
        val count = timelineType.getMethod("i").invoke(timeline) as Int
        if (index !in 0 until count) return emptyList()
        val window = windowType.getConstructor().newInstance()
        val getWindow = timelineType.getMethod("h", Int::class.javaPrimitiveType, windowType)
        val mediaItemField = windowType.getField("c")
        val metadataField = loader.loadClass("v3.t").getField("d")
        fun read(index: Int): AppleArtworkSource.Song? {
            getWindow.invoke(timeline, index, window)
            val nativeItem = mediaItemField.get(window) ?: return null
            return itemMethod.invoke(null, metadataField.get(nativeItem))?.let(::song)
        }
        if (read(index)?.identity != current.identity) return emptyList()
        val wrapMode = if (repeatMode() == 2) 2 else 0
        val shuffle = shuffleEnabled()
        val next = timelineType.getMethod("d", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val previous = timelineType.getMethod("g", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val result = ArrayList<AppleArtworkSource.Song>(6)
        val seen = hashSetOf(index)
        // The timeline knows shuffle order. Never scan or copy an entire library queue.
        for (direction in listOf(previous, next)) {
            var cursor = index
            var remainingNeighbours = 3
            while (remainingNeighbours-- > 0) {
                cursor = direction.invoke(timeline, cursor, wrapMode, shuffle) as Int
                if (cursor !in 0 until count || cursor == index) break
                if (seen.add(cursor)) read(cursor)?.let { result += it }
            }
        }
        return result.distinctBy { it.id }.filter { it.id != current.id }
    }

    private fun song(item: Any): AppleArtworkSource.Song? {
        val id = text(item, "getId").takeIf { it.isNotBlank() && it != "0" } ?: return null
        return AppleArtworkSource.Song(
            id, (item.javaClass.getMethod("getQueueId").invoke(item) as Number).toLong(),
            text(item, "getTitle"), text(item, "getArtistName"), text(item, "getCollectionName"),
            text(item, "getImageUrl"),
        )
    }

    companion object {
        private const val QUEUE_REFRESH_MS = 1_000L
        private fun text(item: Any, method: String) = (item.javaClass.getMethod(method).invoke(item) as? String).orEmpty().trim()
        internal fun matchesOptional(native: String, media: String) = native.isBlank() || media.isBlank() || native == media.trim()
        internal fun favorite(item: Any?): Boolean? = when (item?.javaClass?.getMethod("getLikeState")?.invoke(item)) {
            2 -> true
            1, 3 -> false
            else -> null
        }
        internal fun identity(item: Any): String = "${text(item, "getId")}:${item.javaClass.getMethod("getQueueId").invoke(item)}"
    }
}
