package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricTimeline
import java.lang.reflect.Proxy

/** QQ 20.8.5.8 reflection only. No host classes or opaque objects escape the QQ adapter. */
internal class QQLyricsApi(private val classLoader: ClassLoader) {
    data class Song(val nativeValue: Any, val id: Long)

    private fun hostClass(name: String) = Class.forName(name, false, classLoader)
    private val songClass = hostClass("com.tencent.qqmusicplayerprocess.songinfo.SongInfo")
    private val playEnvironment = hostClass("com.tencent.qqmusic.common.ipc.MusicProcess").getMethod("playEnv")
    private val getPlaySong = hostClass("com.tencent.qqmusic.common.ipc.IPlayProcessMethods").getMethod("getPlaySong")
    private val songTitle = songClass.getMethod("j3")
    private val songId = songClass.getMethod("C3")
    private val loaderClass = hostClass("com.tencent.qqmusic.business.lyricnew.load.manager.m")
    private val listenerClass = hostClass("com.tencent.qqmusic.business.lyricnew.load.listener.c")
    private val addListener = loaderClass.getMethod("c", listenerClass)
    private val loadSong = loaderClass.getMethod("h", songClass)
    private val clearLoader = loaderClass.getMethod("e")
    private val resultClass = hostClass("com.tencent.qqmusic.business.lyricnew.load.model.b")
    private val resultSong = resultClass.getMethod("f")
    private val resultLyrics = resultClass.getMethod("c")
    private val lyricClass = hostClass("com.lyricengine.base.k")
    private val sentences = lyricClass.getField("e")
    private val offset = lyricClass.getField("f")
    private val lyricType = lyricClass.getField("a")
    private val sentenceClass = hostClass("com.lyricengine.base.t")
    private val sentenceText = sentenceClass.getField("a")
    private val sentenceStartTime = sentenceClass.getField("b")

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
        // QQ renders at playbackTime - lyric.f; our media seek time is sentence.b + lyric.f.
        return LyricTimeline.normalize(lines, offset.getInt(lyric).toLong())
    }

    private companion object {
        const val UNTIMED_LYRICS_TYPE = 30
        const val MAX_SENTENCES = 2_000
    }
}
