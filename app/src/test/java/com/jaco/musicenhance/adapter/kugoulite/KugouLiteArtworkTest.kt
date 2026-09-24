package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test

class KugouLiteArtworkTest {
    private fun candidates(url: String?) = KugouLiteArtworkAddresses.candidates(url) { true }.toList()

    @Test fun templatesTryHighResolutionThenSmallerImages() {
        val url = "http://imgessl.kugou.com/stdmusic/{size}/20260924/cover.jpg"
        assertEquals(listOf(0, 1080, 720, 480).map { "https://imgessl.kugou.com/stdmusic/$it/20260924/cover.jpg" }, candidates(url))
    }

    @Test fun fixedLowResolutionAddressIsRetainedAsLastFallback() {
        val url = "https://imgessl.kugou.com/stdmusic/240/cover.jpg"
        assertEquals(listOf(0, 1080, 720, 480, 240).map { url.replace("/240/", "/$it/") }, candidates(url))
    }

    @Test fun trueOriginalPrecedesPotentiallyUpscaledLargeImages() {
        for (size in listOf(0, 1500, 2048)) {
            val url = "https://imgessl.kugou.com/albumcover/$size/cover.jpg"
            assertEquals(url.replace("/$size/", "/0/"), candidates(url).first())
            assertTrue(candidates(url).contains(url))
        }
    }

    @Test fun signedUnknownAndDatePathsArePreserved() {
        for (url in listOf("https://imgessl.kugou.com/stdmusic/240/file.jpg?sign=token",
            "https://imgessl.kugou.com/stdmusic/20260924/file.jpg",
            "https://example.com/stdmusic/240/file.jpg",
            "https://kugou.com.example.com/stdmusic/240/file.jpg")) {
            assertEquals(listOf(url), candidates(url))
        }
    }

    @Test fun placeholdersAndInvalidImagesLeaveNativeFallbackAvailable() {
        for (url in listOf(null, "", "-", "/sdcard/cover.jpg", "file:///sdcard/cover.jpg", "http://example.com/image.jpg")) {
            assertTrue(candidates(url).isEmpty())
        }
    }

    @Test fun cancellationStopsTryingMoreSizesAfterSongChange() {
        var active = true
        val iterator = KugouLiteArtworkAddresses.candidates("https://imgessl.kugou.com/stdmusic/{size}/file.jpg") { active }.iterator()
        assertTrue(iterator.hasNext())
        iterator.next()
        active = false
        assertFalse(iterator.hasNext())
    }

    @Test fun currentSongUsesCurrentServiceMethodAndRejectsSwitchRaces() {
        FakeService.song = FakeSong("current", 1, "Song", "Artist", "https://imgessl.kugou.com/stdmusic/{size}/a.jpg")
        val source = source()
        val player = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist")
        val current = requireNotNull(source.currentSong(player))
        assertEquals("kugoulite:1:current", source.cacheKey(current))
        assertTrue(source.isCurrentSong(current))
        FakeService.song = FakeSong("next", 2, "Song", "Artist", "https://imgessl.kugou.com/stdmusic/{size}/b.jpg")
        assertFalse(source.isCurrentSong(current)) // Same metadata does not mean same recording.
        assertNotEquals(source.cacheKey(current), source.cacheKey(requireNotNull(source.currentSong(player))))
        assertNull(source.currentSong(player.copy(title = "Stale title")))
        assertNull(source.currentSong(player.copy(artist = "Other artist")))
        FakeService.song = FakeSong("", 0, "Song", "Artist", null)
        assertNull(source.currentSong(player))
    }

    @Test fun prefetchReadsOnlyThreeNeighboursOnEachSide() {
        val source = queue(size = 1000, position = 500)
        assertEquals(listOf("501", "499", "502", "498", "503", "497"), neighbours(source))
        assertTrue(FakeService.ranges.all { it.second == 1 })
        assertEquals(setOf(497, 498, 499, 500, 501, 502, 503), FakeService.ranges.map { it.first }.toSet())
    }

    @Test fun circularShortQueuesExcludeCurrentAndDuplicates() {
        val source = queue(size = 3, position = 0)
        assertEquals(listOf("1", "2"), neighbours(source))
        FakeService.queue = listOf(FakeService.queue[0], FakeService.queue[1], FakeService.queue[1])
        assertEquals(listOf("1"), neighbours(source))
    }

    @Test fun nonLoopModesDoNotWrapThePlaylistEdges() {
        val source = queue(size = 10, position = 0)
        for (mode in listOf(2, 3, -1)) {
            FakeService.mode = mode
            assertEquals(listOf("1", "2", "3"), neighbours(source))
        }
    }

    @Test fun queueReplacementAndMidReadSongChangeDiscardPrefetch() {
        val source = queue(size = 10, position = 4)
        FakeService.position = 3
        assertTrue(neighbours(source).isEmpty())
        FakeService.position = 4
        FakeService.afterRange = { FakeService.song = FakeService.queue[5] }
        assertTrue(neighbours(source).isEmpty())
        FakeService.afterRange = null
    }

    private fun source() = KugouLiteArtworkSource(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.kugou.framework.service.util.PlaybackServiceUtil" -> FakeService::class.java
            "com.kugou.framework.service.entity.KGMusicWrapper" -> FakeSong::class.java
            else -> super.loadClass(name)
        }
    })

    private fun queue(size: Int, position: Int): KugouLiteArtworkSource {
        FakeService.queue = List(size) { FakeSong("$it", it.toLong() + 1, "Song", "Artist", null) }
        FakeService.song = FakeService.queue[position]
        FakeService.position = position
        FakeService.mode = 1
        FakeService.ranges.clear()
        FakeService.afterRange = null
        return source()
    }

    private fun neighbours(source: KugouLiteArtworkSource): List<String> = source.neighbours(
        requireNotNull(source.currentSong(PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist"))),
    ).map { it.hash }

    // Host API names are invoked reflectively by the adapter under test.
    @Suppress("unused")
    class FakeSong(private val hash: String, private val id: Long, private val title: String,
        private val artist: String, private val image: String?) {
        fun getHashValue() = hash
        fun getMixId() = id
        fun getTrackName() = title
        fun getArtistName() = artist
        fun b1() = image
    }

    @Suppress("unused")
    object FakeService {
        var song: FakeSong? = null
        var queue = emptyList<FakeSong>()
        var position = 0
        var mode = 1
        val ranges = mutableListOf<Pair<Int, Int>>()
        var afterRange: (() -> Unit)? = null
        @JvmStatic fun s0() = song
        @JvmStatic fun I1(): FakeSong = error("I1 reads the next song")
        @JvmStatic fun V1() = queue.size
        @JvmStatic fun N1() = position
        @JvmStatic fun M1() = mode
        @JvmStatic fun J1(start: Int, count: Int): Array<FakeSong> {
            ranges += start to count
            return queue.subList(start, start + count).toTypedArray().also { afterRange?.invoke() }
        }
    }
}
