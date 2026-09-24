package com.jaco.musicenhance.adapter.kugou

import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test

class KugouArtworkTest {
    @Test fun currentSongUsesCurrentServiceMethodAndRejectsSwitchRaces() {
        FakeService.song = FakeSong("current", 1, "Song", "Artist", "https://imgessl.kugou.com/stdmusic/{size}/a.jpg")
        val source = source()
        val player = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist")
        val current = requireNotNull(source.currentSong(player))
        assertEquals("kugou:1:current:", source.cacheKey(current))
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

    private fun source() = KugouArtworkSource(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.kugou.framework.service.util.PlaybackServiceUtil" -> FakeService::class.java
            "com.kugou.framework.service.entity.KGMusicWrapper" -> FakeSong::class.java
            else -> super.loadClass(name)
        }
    })

    private fun queue(size: Int, position: Int): KugouArtworkSource {
        FakeService.queue = List(size) { FakeSong("$it", it.toLong() + 1, "Song", "Artist", null) }
        FakeService.song = FakeService.queue[position]
        FakeService.position = position
        FakeService.mode = 1
        FakeService.ranges.clear()
        FakeService.afterRange = null
        return source()
    }

    private fun neighbours(source: KugouArtworkSource): List<String> = source.neighbours(
        requireNotNull(source.currentSong(PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist"))),
    ).map { it.identity.hash }

    // Host API names are invoked reflectively by the adapter under test.
    @Suppress("unused")
    class FakeSong(private val hash: String, private val id: Long, private val title: String,
        private val artist: String, private val image: String?) {
        fun getHashValue() = hash
        fun getMixId() = id
        fun getTrackName() = title
        fun getArtistName() = artist
        fun f2() = image
        fun X1(): String? = null
        fun d2() = hash
        fun getExtraId() = ""
    }

    @Suppress("unused")
    object FakeService {
        var song: FakeSong? = null
        var queue = emptyList<FakeSong>()
        var position = 0
        var mode = 1
        val ranges = mutableListOf<Pair<Int, Int>>()
        var afterRange: (() -> Unit)? = null
        @JvmStatic fun Q1() = song
        @JvmStatic fun I1(): FakeSong = error("I1 reads the next song")
        @JvmStatic fun Q4() = queue.size
        @JvmStatic fun v4() = position
        @JvmStatic fun t4() = mode
        @JvmStatic fun l4(start: Int, count: Int): Array<FakeSong> {
            ranges += start to count
            return queue.subList(start, start + count).toTypedArray().also { afterRange?.invoke() }
        }
    }
}
