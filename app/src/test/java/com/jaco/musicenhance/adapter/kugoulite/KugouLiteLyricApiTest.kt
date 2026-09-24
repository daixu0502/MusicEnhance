package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.player.model.LyricLine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KugouLiteLyricApiTest {
    @Test fun currentIdentityUsesPlayingSongRatherThanNextSong() {
        val api = api()
        assertEquals("playing", api.currentSong()?.hash)
        assertEquals("Current title", api.currentSong()?.title)
    }

    @Test fun emptyCachedLyricsAndFirstCandidateDoNotBlockTimedFallback() {
        withLyrics { empty, timed ->
            Database.path = empty.path
            Search.paths = listOf(empty.path, timed.path)
            val api = api()
            val data = requireNotNull(api.load(requireNotNull(api.currentSong())))
            assertEquals(listOf(LyricLine(1200, "Timed line")), api.lines(data))
            assertEquals(Search.paths, Downloader.downloaded)
            assertTrue(Downloader.hashes.all { it == "playing" })
        }
    }

    @Test fun usableCacheAvoidsDownloadAndCandidateFallbackIsBounded() {
        withLyrics { empty, timed ->
            Database.path = timed.path
            val api = api()
            assertNotNull(api.load(requireNotNull(api.currentSong())))
            assertTrue(Downloader.downloaded.isEmpty())
            Database.path = empty.path
            Search.paths = List(5) { empty.path }
            assertNull(api.load(requireNotNull(api.currentSong())))
            assertEquals(3, Downloader.downloaded.size)
        }
    }

    private fun withLyrics(block: (File, File) -> Unit) {
        val empty = File.createTempFile("empty", ".krc")
        val timed = File.createTempFile("timed", ".krc")
        Parser.timedPath = timed.path
        Downloader.downloaded.clear()
        Downloader.hashes.clear()
        try { block(empty, timed) } finally { empty.delete(); timed.delete() }
    }

    private fun api() = KugouLiteLyricApi(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.kugou.framework.service.util.PlaybackServiceUtil" -> Service::class.java
            "com.kugou.framework.lyric.LyricManager" -> Parser::class.java
            "com.kugou.framework.lyric.LyricData" -> Data::class.java
            "com.kugou.framework.database.x0" -> Database::class.java
            "com.kugou.framework.lyric.protocol.d" -> Search::class.java
            "com.kugou.framework.lyric.i" -> Downloader::class.java
            "n70.b" -> Request::class.java
            else -> super.loadClass(name)
        }
    })

    object Service {
        @JvmStatic fun s0() = Song()
        @JvmStatic fun I1(): Song = error("Next song is not the playing song")
    }
    class Song {
        fun getHashValue() = "playing"
        fun getTrackName() = "Current title"
        fun getDisplayName() = "Artist - Current title"
        fun k1() = "audio/mpeg"
        fun T0() = 7L
        fun H0() = 180_000L
        fun getMixId() = 8L
    }
    class Data(private val timed: Boolean) {
        fun r() = if (timed) longArrayOf(1200) else longArrayOf()
        fun E() = if (timed) arrayOf(arrayOf("Timed line")) else emptyArray<Array<String>>()
    }
    class Parsed(@JvmField val e: Data)
    class Parser {
        fun k(path: String, notify: Boolean): Parsed {
            assertFalse(notify)
            return Parsed(Data(path == timedPath))
        }
        companion object {
            var timedPath = ""
            @JvmStatic fun m() = Parser()
        }
    }
    class CachedPath { fun c() = Database.path }
    object Database {
        var path = ""
        @JvmStatic fun f(hash: String): CachedPath { assertEquals("playing", hash); return CachedPath() }
    }
    @Suppress("UNUSED_PARAMETER")
    class Search(name: String, duration: Long, hash: String, mixId: Long) {
        fun s(manual: Boolean) = paths.map(::Candidate)
        companion object { var paths = emptyList<String>() }
    }
    class Candidate(private val path: String) {
        fun b() = "artist"
        fun i() = "title"
        fun f() = path
        fun a() = "access-key"
        fun c() = 1
        fun h() = 0
    }
    @Suppress("UNUSED_PARAMETER")
    class Request {
        var path = ""
        var hash = ""
        fun E(value: String) = Unit
        fun Y(value: String) = Unit
        fun Q(value: String) { path = value }
        fun C(value: String) = Unit
        fun R(value: Int) = Unit
        fun U(value: Int) = Unit
        fun N(value: String) = Unit
        fun O(value: String) { hash = value }
        fun W(value: String) { assertEquals(hash, value) }
        fun S(value: String) = Unit
        fun F(value: Long) = Unit
        fun M(value: Long) = Unit
        fun Z(value: Boolean) = Unit
    }
    @Suppress("UNUSED_PARAMETER")
    class Downloader(private val request: Request, automatic: Boolean) {
        fun v() { downloaded += request.path; hashes += request.hash }
        fun d() = request.path
        companion object {
            val downloaded = mutableListOf<String>()
            val hashes = mutableListOf<String>()
        }
    }
}
