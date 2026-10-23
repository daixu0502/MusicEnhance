package vip.chener.musicenhance.adapter.kugou

import vip.chener.musicenhance.player.model.LyricLine
import vip.chener.musicenhance.adapter.kugoucommon.KugouLyricsRules
import java.io.File

/** 20.8.2 / 20.8.4 contract: native cache/downloader plus a separate KRC/LRC parser. */
internal class KugouLyricsApi(loader: ClassLoader) {
    data class Song(val identity: KugouSongSource.Song, val displayName: String,
        val mimeType: String, val fileId: Long, val durationMs: Long)
    private val songs = KugouSongSource(loader)
    private val interfaces = KugouLyricsRules(loader, "kugou")
    private val songType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val displayName = songType.getMethod("getDisplayName")
    private val mime get() = interfaces.wrapperMethod("mime", "z2", String::class.java)
    private val fileId get() = interfaces.wrapperMethod("file-id", "O1", Long::class.javaPrimitiveType!!)
    private val duration get() = interfaces.wrapperMethod("duration", "D1", Long::class.javaPrimitiveType!!)
    private val downloadConstructor get() = interfaces.downloadConstructor
    private val requestType get() = downloadConstructor.parameterTypes[0]
    private val searchType get() = interfaces.searchType

    fun currentSong(): Song? {
        val native = songs.currentNative() ?: return null
        val identity = songs.read(native) ?: return null
        return Song(identity, displayName.invoke(native) as? String ?: identity.title,
            runCatching { mime.invoke(native) as? String }.getOrNull().orEmpty(),
            runCatching { (fileId.invoke(native) as Number).toLong() }.getOrDefault(0L),
            runCatching { (duration.invoke(native) as Number).toLong() }.getOrDefault(0L))
    }
    fun isCurrent(song: Song) = songs.current()?.key == song.identity.key

    fun currentLines(hash: String): List<LyricLine> {
        val manager = interfaces.activeManager.invoke(null) ?: return emptyList()
        val data = interfaces.activeData.invoke(manager) ?: return emptyList()
        val headers = interfaces.headers.invoke(data) as? Map<*, *>
        if (headers?.values?.none { it is String && it.equals(hash, ignoreCase = true) } != false) return emptyList()
        return lines(data)
    }

    fun load(song: Song, active: () -> Boolean): List<LyricLine> {
        if (!active()) return emptyList()
        // Without a selected candidate the host reuses its local cache or searches automatically.
        download(song, null, active).takeIf { it.isNotEmpty() }?.let { return it }
        if (!active()) return emptyList()
        val search = searchType.constructors.single { it.parameterCount == 5 &&
            it.parameterTypes.take(4) == listOf(String::class.java, Long::class.javaPrimitiveType,
                String::class.java, Long::class.javaPrimitiveType) }
            .newInstance(song.displayName, 1L, song.identity.hash, song.identity.mixId, null)
        val candidates = interfaces.search.invoke(search, false) as? List<*>
        // A cached TXT or a candidate without timestamps must not suppress the timed-lyric fallback.
        for (candidate in candidates.orEmpty().filterNotNull().take(MAX_CANDIDATES)) {
            if (!active()) return emptyList()
            download(song, candidate, active).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun download(song: Song, candidate: Any?, active: () -> Boolean): List<LyricLine> {
        if (!active()) return emptyList()
        val request = requestType.getConstructor().newInstance()
        fun text(method: String, value: String) { interfaces.requestMethod(requestType, method, method,
            Void.TYPE, String::class.java).invoke(request, value) }
        fun number(method: String, value: Long) { interfaces.requestMethod(requestType, method, method,
            Void.TYPE, Long::class.javaPrimitiveType!!).invoke(request, value) }
        text("U", song.displayName)
        text("Y", song.identity.hash)
        text("g0", song.identity.hash)
        text("c0", song.mimeType)
        number("P", song.fileId)
        number("T", song.durationMs)
        number("d0", song.identity.mixId)
        interfaces.requestMethod(requestType, "allow-cache", "m0", Void.TYPE, Boolean::class.javaPrimitiveType!!).invoke(request, true)
        if (candidate != null) {
            fun value(method: String): Any? {
                val resultType = when (method) { "d", "l" -> Int::class.javaPrimitiveType!!; "o" -> Any::class.java; else -> String::class.java }
                return interfaces.candidateMethod(candidate.javaClass, method, method, resultType).invoke(candidate)
            }
            text("K", value("c") as? String ?: "") // singer
            text("l0", value("m") as? String ?: "") // title
            text("k0", value("o")?.toString().orEmpty()) // uploader ID
            text("a0", value("i") as? String ?: "") // lyric ID
            text("H", value("b") as? String ?: "") // access key
            interfaces.requestMethod(requestType, "content-type", "b0", Void.TYPE, Int::class.javaPrimitiveType!!).invoke(request, value("d"))
            interfaces.requestMethod(requestType, "adjust", "e0", Void.TYPE, Int::class.javaPrimitiveType!!).invoke(request, value("l"))
        }
        val downloader = downloadConstructor.newInstance(request, candidate != null)
        interfaces.download.invoke(downloader)
        if (!active()) return emptyList()
        val path = interfaces.downloadedPath.invoke(downloader) as? String
        if (path.isNullOrBlank() || !File(path).isFile) return emptyList()
        val parser = interfaces.independentParser.invoke(null)
        val result = interfaces.parseFile.invoke(parser, path, false)
        val data = result?.let { interfaces.resultData(it.javaClass).get(it) } ?: return emptyList()
        return if (active()) lines(data) else emptyList()
    }

    private fun lines(data: Any) = decodeLines(interfaces.rowStarts.invoke(data) as? LongArray,
        interfaces.words.invoke(data) as? Array<*>)

    companion object {
        private const val MAX_CANDIDATES = 3
        fun decodeLines(startsMs: LongArray?, words: Array<*>?): List<LyricLine> {
            if (startsMs == null || words == null) return emptyList()
            return startsMs.indices.mapNotNull { index ->
                val text = (words.getOrNull(index) as? Array<*>)?.filterIsInstance<String>()?.joinToString("")?.trim().orEmpty()
                if (text.isBlank() || startsMs[index] < 0) null else LyricLine(startsMs[index], text)
            }.sortedBy { it.startMs }
        }
    }
}
