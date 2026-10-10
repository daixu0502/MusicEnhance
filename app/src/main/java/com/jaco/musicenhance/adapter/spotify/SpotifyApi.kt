package com.jaco.musicenhance.adapter.spotify

import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** PlayerState/ContextTrack contract verified in Spotify 9.1.90.2270. No native objects reach UI. */
internal object SpotifyApi {
    private val getters = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    @Volatile var nativeState: Any? = null
    @Volatile private var cached: State? = null
    @Volatile private var cachedNative: Any? = null

    data class Song(val uri: String, val metadata: Map<String, String>) {
        val title get() = metadata["title"].orEmpty()
        fun matches(player: PlayerSnapshot) = title.isNotBlank() && title == player.title &&
            metadata["artist_name"].orEmpty().let { it.isBlank() || it == player.artist } &&
            metadata["album_title"].orEmpty().let { it.isBlank() || player.album.isBlank() || it == player.album }
    }

    data class State(
        val song: Song?, val previous: List<Song>, val next: List<Song>,
        val repeatMode: RepeatMode, val repeatContextAllowed: Boolean,
        val repeatTrackAllowed: Boolean, val shuffleAllowed: Boolean,
    )

    @Synchronized fun state(): State? {
        val native = nativeState ?: return null
        if (cachedNative === native) return cached
        val options = requireNotNull(read(native, "options"))
        val restrictions = requireNotNull(read(native, "restrictions"))
        fun allowed(name: String) = (read(restrictions, name) as? Collection<*>)?.isEmpty() == true
        fun songs(name: String, previous: Boolean): List<Song> {
            val tracks = read(native, name) as? List<*> ?: return emptyList()
            // PlayerState.prevTracks is in playback order; its tail is the nearest predecessor.
            return (if (previous) tracks.takeLast(3).asReversed() else tracks.take(3)).mapNotNull(::song)
        }
        val result = State(
            song(read(native, "track")?.let { optional ->
                val loader = requireNotNull(optional.javaClass.classLoader) { "Spotify track Optional has no host classloader" }
                SpotifyInterfaceRules.optionalValue(loader, optional.javaClass).invoke(optional)
            }),
            songs("prevTracks", true), songs("nextTracks", false),
            when {
                read(options, "shufflingContext") == true -> RepeatMode.SHUFFLE
                read(options, "repeatingTrack") == true -> RepeatMode.SINGLE_LOOP
                read(options, "repeatingContext") == true -> RepeatMode.LIST_LOOP
                else -> RepeatMode.SEQUENTIAL
            },
            allowed("disallowTogglingRepeatContextReasons"),
            allowed("disallowTogglingRepeatTrackReasons"),
            allowed("disallowTogglingShuffleReasons"),
        )
        cached = result
        cachedNative = native
        return result
    }

    fun current(player: PlayerSnapshot): State? = state()?.takeIf { it.song?.matches(player) == true }

    private fun song(track: Any?): Song? {
        track ?: return null
        val uri = read(track, "uri") as? String ?: return null
        if (!uri.startsWith("spotify:track:")) return null
        val metadata = read(track, "metadata") as? Map<*, *> ?: return null
        return Song(uri, metadata.entries.mapNotNull { (key, value) ->
            if (key is String && value is String) key to value else null
        }.toMap())
    }

    fun read(target: Any, name: String): Any? = getters.getOrPut(target.javaClass to name) {
        target.javaClass.getMethod(name).apply { isAccessible = true }
    }.invoke(target)
}
