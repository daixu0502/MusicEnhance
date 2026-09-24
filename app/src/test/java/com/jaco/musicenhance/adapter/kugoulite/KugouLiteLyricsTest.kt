package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.player.model.LyricLine
import org.junit.Assert.*
import org.junit.Test

class KugouLiteLyricsTest {
    @Test fun lyricIdFilenameUsesDownloadIdentityAndRejectsAnotherSong() {
        val store = KugouLiteLyricStore()
        val data = Any()
        store.associate("AUDIO-HASH", "/lyrics/12345.krc")
        store.parsed("/lyrics/12345.krc", data)
        assertSame(data, store.find("audio-hash"))
        assertNull(store.find("another-song"))
        assertNull(store.find(""))
    }

    @Test fun lateOldDownloadCannotReplaceTheNewSongsLyrics() {
        val store = KugouLiteLyricStore()
        val oldData = Any()
        val newData = Any()
        store.associate("new", "/lyrics/new.krc")
        store.parsed("/lyrics/new.krc", newData)
        store.associate("old", "/lyrics/old.krc")
        store.parsed("/lyrics/old.krc", oldData)
        assertSame(newData, store.find("new"))
        assertSame(oldData, store.find("old"))
    }

    @Test fun completedParseCanPrecedeIdentityAndCacheIsBounded() {
        val store = KugouLiteLyricStore()
        val data = Any()
        store.parsed("/lyrics/123.krc", data)
        store.associate("song", "/lyrics/123.krc")
        assertSame(data, store.find("song"))
        repeat(20) { store.put("song-$it", Any()) }
        assertNull(store.find("song-0"))
        assertNotNull(store.find("song-19"))
    }

    @Test fun switchingLyricFileDiscardsThePreviousVersion() {
        val store = KugouLiteLyricStore()
        store.associate("hash", "/lyrics/hash-1.krc")
        store.parsed("/lyrics/hash-1.krc", Any())
        store.associate("hash", "/lyrics/2.krc")
        assertNull(store.find("hash"))
        val replacement = Any()
        store.parsed("/lyrics/2.krc", replacement)
        assertSame(replacement, store.find("hash"))
    }

    @Test fun nativeWordArraysProduceSortedTimedLinesWithMissingRowsIgnored() {
        val lines = KugouLiteLyricApi.decodeLines(
            longArrayOf(2000, -1, 1000, 3000, 4000),
            arrayOf(arrayOf("第", "二句"), arrayOf("无效"), arrayOf("第一句"), arrayOf(" ")),
        )
        assertEquals(listOf(LyricLine(1000, "第一句"), LyricLine(2000, "第二句")), lines)
        assertTrue(KugouLiteLyricApi.decodeLines(null, emptyArray<Any>()).isEmpty())
    }
}
