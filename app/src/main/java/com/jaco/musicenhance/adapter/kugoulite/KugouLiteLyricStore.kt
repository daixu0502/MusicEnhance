package com.jaco.musicenhance.adapter.kugoulite

import java.util.Locale

/** Native lyric filenames may contain a lyric ID, not the audio hash. */
internal class KugouLiteLyricStore {
    private val paths = boundedMap<String, String>()
    private val parsedFiles = boundedMap<String, Any>()
    private val songs = boundedMap<String, Any>()

    @Synchronized fun associate(hash: String?, path: String?) {
        if (hash.isNullOrBlank() || path.isNullOrBlank()) return
        val key = hash.lowercase(Locale.ROOT)
        paths[key] = path
        songs.remove(key)
    }

    @Synchronized fun parsed(path: String?, data: Any?) {
        if (path.isNullOrBlank() || data == null) return
        parsedFiles[path] = data
        paths.filterValues { it == path }.keys.forEach { songs[it] = data }
    }

    @Synchronized fun put(hash: String, data: Any) {
        if (hash.isNotBlank()) songs[hash.lowercase(Locale.ROOT)] = data
    }

    @Synchronized fun find(hash: String): Any? {
        if (hash.isBlank()) return null
        val key = hash.lowercase(Locale.ROOT)
        songs[key]?.let { return it }
        paths[key]?.let { return parsedFiles[it] }
        return parsedFiles.entries.firstOrNull { it.key.contains(key, ignoreCase = true) }?.value
    }

    private fun <K, V> boundedMap(): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_ENTRIES
    }

    private companion object { const val MAX_ENTRIES = 12 }
}
