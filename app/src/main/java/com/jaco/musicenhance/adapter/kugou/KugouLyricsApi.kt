package com.jaco.musicenhance.adapter.kugou

import com.jaco.musicenhance.player.model.LyricLine
import java.io.File

/** 20.8.2 contract: native cache/downloader plus a separate KRC/LRC parser. */
internal class KugouLyricsApi(loader: ClassLoader) {
    data class Song(val identity: KugouSongSource.Song, val displayName: String,
        val mimeType: String, val fileId: Long, val durationMs: Long)
    private val songs = KugouSongSource(loader)
    private val songType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val displayName = songType.getMethod("getDisplayName")
    private val mime = songType.getMethod("z2")
    private val fileId = songType.getMethod("O1")
    private val duration = songType.getMethod("D1")
    private val managerType = loader.loadClass("com.kugou.framework.lyric.LyricManager")
    private val dataType = loader.loadClass("com.kugou.framework.lyric.LyricData")
    private val requestType = loader.loadClass("oe5.b")
    private val downloadType = loader.loadClass("com.kugou.framework.lyric.g")
    private val searchType = loader.loadClass("com.kugou.framework.lyric.protocol.b")
    private val audioKindType = loader.loadClass("com.kugou.common.entity.j")

    fun currentSong(): Song? {
        val native = songs.currentNative() ?: return null
        val identity = songs.read(native) ?: return null
        return Song(identity, displayName.invoke(native) as? String ?: identity.title,
            mime.invoke(native) as? String ?: "", (fileId.invoke(native) as Number).toLong(),
            (duration.invoke(native) as Number).toLong())
    }
    fun isCurrent(song: Song) = songs.current()?.key == song.identity.key

    fun currentLines(hash: String): List<LyricLine> {
        val manager = managerType.getMethod("e").invoke(null) ?: return emptyList()
        val data = managerType.getMethod("f").invoke(manager) ?: return emptyList()
        val headers = dataType.getMethod("getHeaders").invoke(data) as? Map<*, *>
        if (headers?.values?.none { it is String && it.equals(hash, ignoreCase = true) } != false) return emptyList()
        return lines(data)
    }

    fun load(song: Song, active: () -> Boolean): List<LyricLine> {
        if (!active()) return emptyList()
        // Without a selected candidate the host reuses its local cache or searches automatically.
        download(song, null, active).takeIf { it.isNotEmpty() }?.let { return it }
        if (!active()) return emptyList()
        val search = searchType.getConstructor(String::class.java, Long::class.javaPrimitiveType,
            String::class.java, Long::class.javaPrimitiveType, audioKindType)
            .newInstance(song.displayName, 1L, song.identity.hash, song.identity.mixId, null)
        val candidates = searchType.getMethod("y", Boolean::class.javaPrimitiveType).invoke(search, false) as? List<*>
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
        fun text(method: String, value: String) { requestType.getMethod(method, String::class.java).invoke(request, value) }
        fun number(method: String, value: Long) { requestType.getMethod(method, Long::class.javaPrimitiveType).invoke(request, value) }
        text("U", song.displayName)
        text("Y", song.identity.hash)
        text("g0", song.identity.hash)
        text("c0", song.mimeType)
        number("P", song.fileId)
        number("T", song.durationMs)
        number("d0", song.identity.mixId)
        requestType.getMethod("m0", Boolean::class.javaPrimitiveType).invoke(request, true)
        if (candidate != null) {
            fun value(method: String) = candidate.javaClass.getMethod(method).invoke(candidate)
            text("K", value("c") as? String ?: "") // singer
            text("l0", value("m") as? String ?: "") // title
            text("k0", value("o")?.toString().orEmpty()) // uploader ID
            text("a0", value("i") as? String ?: "") // lyric ID
            text("H", value("b") as? String ?: "") // access key
            requestType.getMethod("b0", Int::class.javaPrimitiveType).invoke(request, value("d"))
            requestType.getMethod("e0", Int::class.javaPrimitiveType).invoke(request, value("l"))
        }
        val downloader = downloadType.getConstructor(requestType, Boolean::class.javaPrimitiveType)
            .newInstance(request, candidate != null)
        downloadType.getMethod("G").invoke(downloader)
        if (!active()) return emptyList()
        val path = downloadType.getMethod("f").invoke(downloader) as? String
        if (path.isNullOrBlank() || !File(path).isFile) return emptyList()
        val parser = managerType.getMethod("n").invoke(null)
        val result = managerType.getMethod("l", String::class.java, Boolean::class.javaPrimitiveType).invoke(parser, path, false)
        val data = result?.javaClass?.getField("e")?.get(result) ?: return emptyList()
        return if (active()) lines(data) else emptyList()
    }

    private fun lines(data: Any) = decodeLines(dataType.getMethod("getRowBeginTime").invoke(data) as? LongArray,
        dataType.getMethod("getWords").invoke(data) as? Array<*>)

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
