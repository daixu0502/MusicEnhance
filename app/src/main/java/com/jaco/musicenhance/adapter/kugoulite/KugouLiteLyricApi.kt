package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.player.model.LyricLine
import java.io.File

/** Reflection contract verified against concept edition 5.2.9; no dependency in shared UI. */
internal class KugouLiteLyricApi(loader: ClassLoader) {
    private val service = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val managerType by lazy { loader.loadClass("com.kugou.framework.lyric.LyricManager") }
    private val dataType by lazy { loader.loadClass("com.kugou.framework.lyric.LyricData") }
    private val requestType by lazy { loader.loadClass("n70.b") }
    private val downloadType by lazy { loader.loadClass("com.kugou.framework.lyric.i") }
    private val databaseType by lazy { loader.loadClass("com.kugou.framework.database.x0") }
    private val searchType by lazy { loader.loadClass("com.kugou.framework.lyric.protocol.d") }
    private val currentSongMethod = service.getMethod("s0")

    data class Song(
        val hash: String,
        val title: String,
        val displayName: String,
        val mimeType: String,
        val fileId: Long,
        val durationMs: Long,
        val mixId: Long,
    )

    fun currentSong(): Song? {
        // I1 returns the NEXT song, which fails metadata matching before any lyrics can load.
        val song = currentSongMethod.invoke(null) ?: return null
        fun number(getter: String) = (song.javaClass.getMethod(getter).invoke(song) as Number).toLong()
        return Song(
            songString(song, "getHashValue"), songString(song, "getTrackName"),
            songString(song, "getDisplayName"), songString(song, "k1"),
            number("T0"), number("H0"), number("getMixId"),
        )
    }

    fun currentData(hash: String): Any? {
        val manager = managerType.getMethod("d").invoke(null) ?: return null
        val data = managerType.getMethod("e").invoke(manager) ?: return null
        val headers = dataType.getMethod("l").invoke(data) as? Map<*, *>
        return data.takeIf { headers?.values?.any { it is String && it.equals(hash, ignoreCase = true) } == true }
    }

    fun load(song: Song): Any? {
        val hash = song.hash
        if (hash.isBlank() || Thread.currentThread().isInterrupted) return null
        val cached = databaseType.getMethod("f", String::class.java).invoke(null, hash)
        val cachedPath = cached?.javaClass?.getMethod("c")?.invoke(cached) as? String
        parse(cachedPath)?.takeIf { lines(it).isNotEmpty() }?.let { return it }
        // The lyric ID and access key come from the host's search response, not the audio hash.
        val search = searchType.getConstructor(
            String::class.java, Long::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
        ).newInstance(song.displayName, 1L, hash, song.mixId)
        val candidates = searchType.getMethod("s", Boolean::class.javaPrimitiveType).invoke(search, false) as? List<*>
        for (candidate in candidates.orEmpty().filterNotNull().take(MAX_CANDIDATES)) {
            if (Thread.currentThread().isInterrupted) return null
            download(song, candidate)?.takeIf { lines(it).isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun download(song: Song, candidate: Any): Any? {
        if (Thread.currentThread().isInterrupted) return null
        fun candidateValue(getter: String): Any? = candidate.javaClass.getMethod(getter).invoke(candidate)
        val request = requestType.getConstructor().newInstance()
        // Mirror AsyncLyricLoader's KGMusicWrapper request; capture both hash fields before
        // downloading instead of reading the playing-song hash again after a possible skip.
        fun string(name: String, value: String) = requestType.getMethod(name, String::class.java).invoke(request, value)
        fun number(name: String, value: Long) = requestType.getMethod(name, Long::class.javaPrimitiveType).invoke(request, value)
        string("E", candidateValue("b") as? String ?: "")
        string("Y", candidateValue("i") as? String ?: "")
        string("Q", candidateValue("f").toString())
        string("C", candidateValue("a") as? String ?: "")
        requestType.getMethod("R", Int::class.javaPrimitiveType).invoke(request, candidateValue("c"))
        requestType.getMethod("U", Int::class.javaPrimitiveType).invoke(request, candidateValue("h"))
        string("N", song.displayName)
        string("O", song.hash)
        string("W", song.hash)
        string("S", song.mimeType)
        number("F", song.fileId)
        number("M", song.durationMs)
        requestType.getMethod("Z", Boolean::class.javaPrimitiveType).invoke(request, true)
        val download = downloadType.getConstructor(requestType, Boolean::class.javaPrimitiveType).newInstance(request, true)
        downloadType.getMethod("v").invoke(download)
        if (Thread.currentThread().isInterrupted) return null
        val path = downloadType.getMethod("d").invoke(download) as? String
        return parse(path)
    }

    private fun parse(path: String?): Any? {
        if (path.isNullOrBlank() || !File(path).isFile) return null
        // m() creates a separate parser: never replace or notify the host's active LyricManager.
        val parser = managerType.getMethod("m").invoke(null)
        val result = managerType.getMethod("k", String::class.java, Boolean::class.javaPrimitiveType).invoke(parser, path, false)
        return result?.javaClass?.getField("e")?.get(result)
    }

    fun lines(data: Any): List<LyricLine> = decodeLines(
        dataType.getMethod("r").invoke(data) as? LongArray,
        dataType.getMethod("E").invoke(data) as? Array<*>,
    )

    private fun songString(song: Any, getter: String): String =
        song.javaClass.getMethod(getter).invoke(song) as? String ?: ""

    companion object {
        private const val MAX_CANDIDATES = 3
        internal fun decodeLines(startsMs: LongArray?, words: Array<*>?): List<LyricLine> {
            startsMs ?: return emptyList()
            words ?: return emptyList()
            return startsMs.indices.mapNotNull { index ->
                val text = (words.getOrNull(index) as? Array<*>)?.filterIsInstance<String>()?.joinToString("")?.trim().orEmpty()
                if (text.isBlank() || startsMs[index] < 0) null else LyricLine(startsMs[index], text)
            }.sortedBy { it.startMs }
        }
    }
}
