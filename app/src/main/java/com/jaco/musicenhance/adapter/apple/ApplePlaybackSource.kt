package com.jaco.musicenhance.adapter.apple

import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import java.util.concurrent.Executor

/** Apple Music 6.5.2. Read the process browser on its UI thread, not a stopped player Fragment. */
internal class ApplePlaybackSource(
    private val loader: ClassLoader,
    private val favoriteExecutor: Executor = AppleFavoriteSource.worker,
) {
    private val api by lazy { AppleApi(loader) }
    private val controllerType get() = api.controllerType
    private val metadataMethod get() = api.metadata
    private val itemMethod get() = api.convertItem
    private val favoriteSource = lazy { AppleFavoriteSource(loader, favoriteExecutor) }
    private val favorites by favoriteSource
    private val timelineType by lazy { controllerType.getMethod("getCurrentTimeline").returnType }
    private val windowMethod by lazy {
        HostInterfaces.method(loader, "apple.queue.window.v1", known = {
            timelineType.getMethod("h", Int::class.javaPrimitiveType, loader.loadClass("v3.J\$d"))
        }, validate = { it.parameterCount == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType && it.returnType == it.parameterTypes[1] }) {
            methods { matcher { declaredClass = timelineType.name; name = "getWindow"; paramCount = 2 } }
                .map { it.getMethodInstance(loader) }
        }
    }
    private val windowType get() = windowMethod.parameterTypes[1]
    private var lastMetadata: Any? = null
    private var lastItem: Any? = null
    private var nextQueueReadAtMs = 0L
    private var lastQueueFailure: String? = null
    @Volatile var artwork: AppleArtworkSource.Playlist? = null
        private set

    fun browser(): Any? = api.browser.get(null)

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
        api.commandAvailable.invoke(it, 15) == true
    } ?: false

    fun setRepeatMode(mode: Int, shuffle: Boolean = false): Boolean {
        val browser = browser() ?: return false
        if (!canSetRepeat()) return false
        val changeShuffle = shuffleEnabled() != shuffle
        if (changeShuffle) {
            if (api.commandAvailable.invoke(browser, 14) != true) return false
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
        val index = api.currentIndex.invoke(browser) as Int
        val count = queueMethod("count", "i", "getWindowCount", Int::class.javaPrimitiveType!!).invoke(timeline) as Int
        if (index !in 0 until count) return emptyList()
        val window = windowType.getConstructor().newInstance()
        val getWindow = windowMethod
        val mediaItemField = HostInterfaces.field(loader, "apple.queue.item.v1", known = { windowType.getField("c") }) {
            windowType.declaredFields.filter { field ->
                field.type.declaredFields.any { it.type == metadataMethod.returnType }
            }
        }
        val metadataField = HostInterfaces.field(loader, "apple.queue.metadata.v1", known = { loader.loadClass("v3.t").getField("d") },
            validate = { it.type == metadataMethod.returnType }) {
            mediaItemField.type.declaredFields.filter { it.type == metadataMethod.returnType }
        }
        fun read(index: Int): AppleArtworkSource.Song? {
            getWindow.invoke(timeline, index, window)
            val nativeItem = mediaItemField.get(window) ?: return null
            return itemMethod.invoke(null, metadataField.get(nativeItem))?.let(::song)
        }
        if (read(index)?.identity != current.identity) return emptyList()
        val wrapMode = if (repeatMode() == 2) 2 else 0
        val shuffle = shuffleEnabled()
        val params = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        val next = queueMethod("next", "d", "getNextWindowIndex", Int::class.javaPrimitiveType!!, *params)
        val previous = queueMethod("previous", "g", "getPreviousWindowIndex", Int::class.javaPrimitiveType!!, *params)
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

    private fun queueMethod(key: String, known: String, semantic: String, result: Class<*>, vararg params: Class<*>) =
        HostInterfaces.method(loader, "apple.queue.$key.v1", known = { timelineType.getMethod(known, *params) },
            validate = { it.returnType == result && it.parameterTypes.contentEquals(params) }) {
            methods { matcher { declaredClass = timelineType.name; name = semantic; paramTypes(*params); returnType = result.name } }
                .map { it.getMethodInstance(loader) }
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
