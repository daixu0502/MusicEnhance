package com.jaco.musicenhance.player.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistArtworkWindowTest {
    @Test fun selectsThreeOnEachSideNearestFirst() {
        assertEquals(listOf(6, 4, 7, 3, 8, 2), PlaylistArtworkWindow.indices(12, 5, wrap = false))
    }

    @Test fun sequentialEdgesDoNotWrap() {
        assertEquals(listOf(1, 2, 3), PlaylistArtworkWindow.indices(10, 0, wrap = false))
        assertEquals(listOf(8, 7, 6), PlaylistArtworkWindow.indices(10, 9, wrap = false))
    }

    @Test fun circularEdgesIncludeTheOppositeEnd() {
        assertEquals(listOf(1, 9, 2, 8, 3, 7), PlaylistArtworkWindow.indices(10, 0, wrap = true))
        assertEquals(listOf(0, 8, 1, 7, 2, 6), PlaylistArtworkWindow.indices(10, 9, wrap = true))
    }

    @Test fun shortAndInvalidQueuesNeverRepeatTheCurrentTrack() {
        assertEquals(listOf(1), PlaylistArtworkWindow.indices(2, 0, wrap = true))
        assertEquals(listOf(1, 2), PlaylistArtworkWindow.indices(3, 0, wrap = true))
        assertEquals(emptyList<Int>(), PlaylistArtworkWindow.indices(1, 0, wrap = true))
        assertEquals(emptyList<Int>(), PlaylistArtworkWindow.indices(0, 0, wrap = true))
        assertEquals(emptyList<Int>(), PlaylistArtworkWindow.indices(10, -1, wrap = false))
        assertEquals(emptyList<Int>(), PlaylistArtworkWindow.indices(10, 10, wrap = true))
    }
}
