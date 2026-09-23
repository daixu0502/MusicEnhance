package com.jaco.musicenhance.player.artwork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainsRecentArtworkWithinTheByteBudget() {
        val directory = temporary.newFolder()
        val cache = ArtworkDiskCache(directory, maxBytes = 12)
        cache.write("first", ByteArray(6) { 1 })
        directory.listFiles()!!.single().setLastModified(1)
        cache.write("second", ByteArray(6) { 2 })
        directory.listFiles()!!.forEach { if (it.lastModified() != 1L) it.setLastModified(2) }
        assertArrayEquals(ByteArray(6) { 1 }, cache.read("first")) // Refresh the older entry.
        cache.write("third", ByteArray(6) { 3 })
        assertTrue(cache.contains("first"))
        assertNull(cache.read("second"))
        assertTrue(cache.contains("third"))
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 12)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun diskEntriesSurviveCacheRecreationAndRemainIndependent() {
        val directory = temporary.newFolder()
        val cache = ArtworkDiskCache(directory)
        cache.write("https://a.test/cover?album=1", byteArrayOf(1, 2, 3))
        cache.write("https://a.test/cover?album=2", byteArrayOf(4, 5, 6))
        val reopened = ArtworkDiskCache(directory)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.read("https://a.test/cover?album=1"))
        assertArrayEquals(byteArrayOf(4, 5, 6), reopened.read("https://a.test/cover?album=2"))
        reopened.remove("https://a.test/cover?album=1")
        assertFalse(reopened.contains("https://a.test/cover?album=1"))
        assertTrue(reopened.contains("https://a.test/cover?album=2"))
    }

    @Test fun oversizedImagesAndUnavailableDirectoriesDoNotBreakPlayback() {
        val cache = ArtworkDiskCache(temporary.newFolder(), maxBytes = 2)
        cache.write("large", byteArrayOf(1, 2, 3))
        assertFalse(cache.contains("large"))
        val unavailable = ArtworkDiskCache(temporary.newFile())
        unavailable.write("cover", byteArrayOf(1))
        assertNull(unavailable.read("cover"))
    }
}
