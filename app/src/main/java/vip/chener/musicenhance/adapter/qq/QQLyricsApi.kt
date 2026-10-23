package vip.chener.musicenhance.adapter.qq

import vip.chener.musicenhance.player.model.LyricLine
import vip.chener.musicenhance.player.model.LyricTimeline
import java.lang.reflect.Proxy

/** Discovered native lyric protocol. Host objects stay inside the QQ adapter. */
internal class QQLyricsApi(private val classLoader: ClassLoader) {
    data class Song(val nativeValue: Any, val id: Long)

    private fun hostClass(name: String) = classLoader.loadClass(name)
    private val songClass = hostClass("com.tencent.qqmusicplayerprocess.songinfo.SongInfo")
    private val process = QQPlayProcessApi(classLoader)
    private val playEnvironment = process.getEnvironment
    private val getPlaySong = process.type.getMethod("getPlaySong")
    private val songTitle = QQInterfaceRules.songTitle(classLoader, songClass)
    private val songId = QQInterfaceRules.songId(classLoader, songClass)
    private val loaderClass = QQInterfaceRules.lyricLoader(classLoader)
    private val addListener = QQInterfaceRules.addLyricListener(classLoader, loaderClass)
    private val listenerClass = addListener.parameterTypes.single()
    private val loadSong = QQInterfaceRules.loadLyrics(classLoader, loaderClass, songClass)
    private val clearLoader = QQInterfaceRules.clearLyrics(classLoader, loaderClass)
    private val resultClass = listenerClass.methods.single {
        it.name == "onLoadSuc" && it.parameterCount == 1
    }.parameterTypes.single()
    private val resultSong = QQInterfaceRules.resultSong(classLoader, resultClass, songClass)
    private val resultLyrics = QQInterfaceRules.resultLyrics(classLoader, resultClass)
    private val lyricClass = resultLyrics.returnType
    private val sentences = QQInterfaceRules.lyricSentences(classLoader, lyricClass)
    private val offset = QQInterfaceRules.lyricOffset(classLoader, lyricClass)
    private val lyricType = QQInterfaceRules.lyricType(classLoader, lyricClass)
    private val sentenceStartTime = QQInterfaceRules.sentenceStart(classLoader, lyricClass)
    private val sentenceText = QQInterfaceRules.sentenceText(classLoader, sentenceStartTime.declaringClass)

    /** May perform IPC; call from a worker. */
    fun currentSong(expectedTitle: String): Song? {
        val song = getPlaySong.invoke(playEnvironment.invoke(null)) ?: return null
        if (songTitle.invoke(song) != expectedTitle) return null
        return Song(song, (songId.invoke(song) as Number).toLong())
    }

    /** Own instance: never replaces QQ's global loader or listeners. Use on the main thread. */
    fun createLoader(onLoaded: (Any) -> Unit): Loader {
        val nativeLoader = loaderClass.getConstructor().newInstance()
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MusicEnhanceLyricListener"
                "onLoadSuc" -> { args?.firstOrNull()?.let(onLoaded); null }
                // Other callbacks include intermediate states, not only terminal failures.
                else -> null
            }
        }
        try {
            addListener.invoke(nativeLoader, listener)
        } catch (error: Throwable) {
            runCatching { clearLoader.invoke(nativeLoader) }
            throw error
        }
        return Loader(nativeLoader)
    }

    inner class Loader internal constructor(private val nativeLoader: Any) {
        fun start(song: Song) { loadSong.invoke(nativeLoader, song.nativeValue) }
        fun release() { clearLoader.invoke(nativeLoader) }
    }

    /** null means a stale/wrong-song callback; an empty list means no timed lyrics. */
    fun decode(result: Any, expectedSongId: Long): List<LyricLine>? {
        val song = resultSong.invoke(result) ?: return null
        if ((songId.invoke(song) as Number).toLong() != expectedSongId) return null
        val lyric = resultLyrics.invoke(result) ?: return emptyList()
        if (lyricType.getInt(lyric) == UNTIMED_LYRICS_TYPE) return emptyList()
        val lines = (sentences.get(lyric) as? Iterable<*>)?.asSequence().orEmpty()
            .filterNotNull().take(MAX_SENTENCES).map { sentence ->
                LyricLine(sentenceStartTime.getLong(sentence), (sentenceText.get(sentence) as? String).orEmpty())
            }.toList()
        // QQ renders at playbackTime - fileOffset; seek targets include that same offset.
        return LyricTimeline.normalize(lines, offset.getInt(lyric).toLong())
    }

    private companion object {
        const val UNTIMED_LYRICS_TYPE = 30
        const val MAX_SENTENCES = 2_000
    }
}
