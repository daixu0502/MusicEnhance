package com.jaco.musicenhance.player.lyrics

import com.jaco.musicenhance.player.media.MediaSessionPlayerController
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LyricsProviderTest {
    @Test fun unadaptedPlayerReportsUnsupportedWithoutInventingLyrics() {
        val controller = MediaSessionPlayerController("Other player")
        val lyrics = controller.lyrics(PlayerSnapshot.Empty)
        assertEquals(LyricsStatus.UNSUPPORTED, lyrics.status)
        assertEquals(emptyList<LyricLine>(), lyrics.lines)
        assertSame(lyrics, controller.lyrics(PlayerSnapshot.Empty))
    }

    @Test fun anotherPlayerCanSupplyLyricsAndReleaseWithoutDependingOnQQ() {
        var receivedPlayer: PlayerSnapshot? = null
        var releaseCount = 0
        val expected = LyricsSnapshot("other:42", LyricsStatus.READY, listOf(LyricLine(500, "歌词")))
        val provider = object : LyricsProvider {
            override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
                receivedPlayer = player
                return expected
            }
            override fun release() { releaseCount++ }
        }
        val controller = MediaSessionPlayerController("Other player", provider)
        val player = PlayerSnapshot.Empty.copy(title = "歌曲", durationMs = 2_000)
        assertSame(expected, controller.lyrics(player))
        assertSame(player, receivedPlayer)
        controller.release()
        assertEquals(1, releaseCount)
        assertSame(expected, controller.lyrics(player))
    }
}
