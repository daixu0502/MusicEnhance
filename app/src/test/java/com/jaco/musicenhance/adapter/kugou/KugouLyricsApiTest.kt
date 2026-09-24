package com.jaco.musicenhance.adapter.kugou

import com.jaco.musicenhance.player.model.LyricLine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KugouLyricsApiTest {
    @Test fun automaticNativeCacheAvoidsCandidateSearch() = withLyrics { _, timed ->
        Downloader.cachedPath = timed.path
        val api = api()
        assertEquals("playing", api.currentSong()?.identity?.hash)
        assertEquals(listOf(LyricLine(1200, "Timed line")), api.load(requireNotNull(api.currentSong())) { true })
        assertEquals(0, Search.calls)
    }

    @Test fun untimedCacheAndFirstCandidateDoNotBlockTimedFallback() = withLyrics { empty, timed ->
        Downloader.cachedPath = empty.path
        Search.paths = listOf(empty.path, timed.path)
        val api = api()
        assertEquals(listOf(LyricLine(1200, "Timed line")), api.load(requireNotNull(api.currentSong())) { true })
        assertEquals(Search.paths, Downloader.candidates)
    }

    @Test fun candidateAttemptsAreBoundedAndCancellationStopsDownloading() = withLyrics { empty, _ ->
        Downloader.cachedPath = empty.path
        Search.paths = List(5) { empty.path }
        val api = api()
        assertTrue(api.load(requireNotNull(api.currentSong())) { true }.isEmpty())
        assertEquals(3, Downloader.candidates.size)
        var active = true
        Downloader.candidates.clear()
        Downloader.afterDownload = { active = false }
        assertTrue(api.load(requireNotNull(api.currentSong())) { active }.isEmpty())
        assertTrue(Downloader.candidates.isEmpty())
    }

    @Test fun activeLyricsRequireMatchingAudioHash() {
        val api = api()
        assertTrue(api.currentLines("other-song").isEmpty())
        assertEquals(listOf(LyricLine(1200, "Timed line")), api.currentLines("PLAYING"))
    }

    @Test fun malformedAndMissingRowsAreIgnoredWithoutInventingTimestamps() {
        assertEquals(listOf(LyricLine(100, "Hello world")), KugouLyricsApi.decodeLines(
            longArrayOf(-1, 100, 300, 400), arrayOf(arrayOf("invalid"), arrayOf("Hello ", "world"), arrayOf(" "))))
        assertTrue(KugouLyricsApi.decodeLines(null, emptyArray<Any>()).isEmpty())
    }

    private fun withLyrics(block: (File, File) -> Unit) {
        val empty = File.createTempFile("empty", ".krc")
        val timed = File.createTempFile("timed", ".krc")
        Parser.timedPath = timed.path
        Downloader.candidates.clear()
        Downloader.afterDownload = {}
        Search.calls = 0
        Search.paths = emptyList()
        try { block(empty, timed) } finally { empty.delete(); timed.delete() }
    }

    private fun api() = KugouLyricsApi(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.kugou.framework.service.util.PlaybackServiceUtil" -> Service::class.java
            "com.kugou.framework.service.entity.KGMusicWrapper" -> Song::class.java
            "com.kugou.framework.lyric.LyricManager" -> Parser::class.java
            "com.kugou.framework.lyric.LyricData" -> Data::class.java
            "com.kugou.framework.lyric.protocol.b" -> Search::class.java
            "com.kugou.framework.lyric.g" -> Downloader::class.java
            "com.kugou.common.entity.j" -> AudioKind::class.java
            "oe5.b" -> Request::class.java
            else -> super.loadClass(name)
        }
    })

    @Suppress("unused") object Service { @JvmStatic fun Q1() = Song() }
    @Suppress("unused") class Song {
        fun getHashValue() = "playing"
        fun getMixId() = 8L
        fun getExtraId() = ""
        fun getArtistName() = "Artist"
        fun getTrackName() = "Song"
        fun getDisplayName() = "Artist - Song"
        fun z2() = "audio/mpeg"
        fun O1() = 7L
        fun D1() = 180_000L
    }
    @Suppress("unused") class Data(private val timed: Boolean) {
        fun getRowBeginTime() = if (timed) longArrayOf(1200) else longArrayOf()
        fun getWords() = if (timed) arrayOf(arrayOf("Timed line")) else emptyArray<Array<String>>()
        fun getHeaders() = mapOf("hash" to "playing")
    }
    @Suppress("unused") class Parsed(@JvmField val e: Data)
    @Suppress("unused") class Parser {
        fun l(path: String, notify: Boolean): Parsed { assertFalse(notify); return Parsed(Data(path == timedPath)) }
        fun f() = Data(true)
        companion object {
            var timedPath = ""
            @JvmStatic fun n() = Parser()
            @JvmStatic fun e() = Parser()
        }
    }
    class AudioKind
    @Suppress("UNUSED_PARAMETER", "unused") class Search(name: String, duration: Long, hash: String, mixId: Long, kind: AudioKind?) {
        fun y(manual: Boolean): List<Candidate> { calls++; return paths.map(::Candidate) }
        companion object { var paths = emptyList<String>(); var calls = 0 }
    }
    @Suppress("unused") class Candidate(private val path: String) {
        fun c() = "artist"
        fun m() = "title"
        fun o() = 1L
        fun i() = path
        fun b() = "access-key"
        fun d() = 1
        fun l() = 0
    }
    @Suppress("UNUSED_PARAMETER", "unused") class Request {
        var path = ""
        var hash = ""
        fun U(value: String) = Unit
        fun Y(value: String) { hash = value }
        fun g0(value: String) { assertEquals(hash, value) }
        fun c0(value: String) = Unit
        fun P(value: Long) = Unit
        fun T(value: Long) = Unit
        fun d0(value: Long) = Unit
        fun m0(value: Boolean) = Unit
        fun K(value: String) = Unit
        fun l0(value: String) = Unit
        fun k0(value: String) = Unit
        fun a0(value: String) { path = value }
        fun H(value: String) = Unit
        fun b0(value: Int) = Unit
        fun e0(value: Int) = Unit
    }
    @Suppress("unused") class Downloader(private val request: Request, private val selectedCandidate: Boolean) {
        fun G() {
            assertEquals("playing", request.hash)
            if (selectedCandidate) candidates += request.path
            afterDownload()
        }
        fun f() = if (selectedCandidate) request.path else cachedPath
        companion object {
            var cachedPath = ""
            val candidates = mutableListOf<String>()
            var afterDownload: () -> Unit = {}
        }
    }
}
