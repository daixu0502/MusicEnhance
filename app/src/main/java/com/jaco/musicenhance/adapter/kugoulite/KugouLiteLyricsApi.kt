package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.adapter.kugoucommon.KugouLyricsRules
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.shape
import java.io.File

/** Reflection contract verified against concept edition 5.2.9; no dependency in shared UI. */
internal class KugouLiteLyricsApi(private val loader: ClassLoader) {
    private val api = KugouLiteApi(loader)
    private val interfaces = KugouLyricsRules(loader, "kugoulite")
    private val requestType get() = interfaces.downloadConstructor.parameterTypes[0]
    private val searchType get() = interfaces.searchType
    private val currentSongMethod = api.currentSong
    private val cachedRecord get() = HostInterfaces.method(loader, "kugoulite.lyrics.cached-record.v1", {
        loader.loadClass("com.kugou.framework.database.x0").getMethod("f", String::class.java)
    }, { it.shape(listOf("java.lang.String"), loader.loadClass("com.kugou.framework.lyric.entiy.LyricListBean").name, true) }) {
        methods { searchPackages("com.kugou.framework.database"); matcher {
            paramTypes("java.lang.String"); returnType("com.kugou.framework.lyric.entiy.LyricListBean");
            usingEqStrings("lyric_song_hash =? COLLATE NOCASE")
        } }.filter { it.invokes.any { call -> call.name == "get" && call.className == "java.util.ArrayList" } }
            .map { it.getMethodInstance(loader) }
    }

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
        fun optional(semantic: String, getter: String, type: Class<*>): Any? = runCatching {
            interfaces.wrapperMethod(semantic, getter, type).invoke(song)
        }.getOrNull()
        return Song(
            songString(song, "getHashValue"), songString(song, "getTrackName"),
            songString(song, "getDisplayName"), optional("mime", "k1", String::class.java) as? String ?: "",
            (optional("file-id", "T0", Long::class.javaPrimitiveType!!) as? Number)?.toLong() ?: 0L,
            (optional("duration", "H0", Long::class.javaPrimitiveType!!) as? Number)?.toLong() ?: 0L,
            (song.javaClass.getMethod("getMixId").invoke(song) as Number).toLong(),
        )
    }

    fun currentData(hash: String): Any? {
        val manager = interfaces.activeManager.invoke(null) ?: return null
        val data = interfaces.activeData.invoke(manager) ?: return null
        val headers = interfaces.headers.invoke(data) as? Map<*, *>
        return data.takeIf { headers?.values?.any { it is String && it.equals(hash, ignoreCase = true) } == true }
    }

    fun load(song: Song): Any? {
        val hash = song.hash
        if (hash.isBlank() || Thread.currentThread().isInterrupted) return null
        val cached = cachedRecord.invoke(null, hash)
        val cachedPath = cached?.let { api.verified("lyrics.cached-path", it.javaClass, "c", String::class.java).invoke(it) } as? String
        parse(cachedPath)?.takeIf { lines(it).isNotEmpty() }?.let { return it }
        // The lyric ID and access key come from the host's search response, not the audio hash.
        val search = searchType.getConstructor(
            String::class.java, Long::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
        ).newInstance(song.displayName, 1L, hash, song.mixId)
        val candidates = interfaces.search.invoke(search, false) as? List<*>
        for (candidate in candidates.orEmpty().filterNotNull().take(MAX_CANDIDATES)) {
            if (Thread.currentThread().isInterrupted) return null
            download(song, candidate)?.takeIf { lines(it).isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun download(song: Song, candidate: Any): Any? {
        if (Thread.currentThread().isInterrupted) return null
        fun candidateValue(getter: String): Any? {
            val resultType = when (getter) { "c", "h" -> Int::class.javaPrimitiveType!!; "f" -> Any::class.java; else -> String::class.java }
            return interfaces.candidateMethod(candidate.javaClass, getter, getter, resultType).invoke(candidate)
        }
        val request = requestType.getConstructor().newInstance()
        // Mirror AsyncLyricLoader's KGMusicWrapper request; capture both hash fields before
        // downloading instead of reading the playing-song hash again after a possible skip.
        fun string(name: String, value: String) = interfaces.requestMethod(requestType, name, name,
            Void.TYPE, String::class.java).invoke(request, value)
        fun number(name: String, value: Long) = interfaces.requestMethod(requestType, name, name,
            Void.TYPE, Long::class.javaPrimitiveType!!).invoke(request, value)
        string("E", candidateValue("b") as? String ?: "")
        string("Y", candidateValue("i") as? String ?: "")
        string("Q", candidateValue("f").toString())
        string("C", candidateValue("a") as? String ?: "")
        interfaces.requestMethod(requestType, "content-type", "R", Void.TYPE, Int::class.javaPrimitiveType!!).invoke(request, candidateValue("c"))
        interfaces.requestMethod(requestType, "adjust", "U", Void.TYPE, Int::class.javaPrimitiveType!!).invoke(request, candidateValue("h"))
        string("N", song.displayName)
        string("O", song.hash)
        string("W", song.hash)
        string("S", song.mimeType)
        number("F", song.fileId)
        number("M", song.durationMs)
        interfaces.requestMethod(requestType, "allow-cache", "Z", Void.TYPE, Boolean::class.javaPrimitiveType!!).invoke(request, true)
        val download = interfaces.downloadConstructor.newInstance(request, true)
        interfaces.download.invoke(download)
        if (Thread.currentThread().isInterrupted) return null
        val path = interfaces.downloadedPath.invoke(download) as? String
        return parse(path)
    }

    private fun parse(path: String?): Any? {
        if (path.isNullOrBlank() || !File(path).isFile) return null
        // m() creates a separate parser: never replace or notify the host's active LyricManager.
        val parser = interfaces.independentParser.invoke(null)
        val result = interfaces.parseFile.invoke(parser, path, false)
        return result?.let { interfaces.resultData(it.javaClass).get(it) }
    }

    fun lines(data: Any): List<LyricLine> = decodeLines(
        interfaces.rowStarts.invoke(data) as? LongArray,
        interfaces.words.invoke(data) as? Array<*>,
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
