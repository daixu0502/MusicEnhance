package com.jaco.musicenhance.player.lyrics

import android.os.Looper
import com.jaco.musicenhance.player.EnhancedPlayerController
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.PlayerActions
import com.jaco.musicenhance.player.TestPlayerDataSource
import com.jaco.musicenhance.player.model.PlayerDisplayState
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LyricsProviderTest {
    @Test fun unadaptedPlayerReportsUnsupportedWithoutInventingLyrics() {
        val controller = EnhancedPlayerController(PlayerSession("Other player", TestPlayerDataSource(), PlayerActions({}, {}, {}, {}, {})))
        var state = PlayerDisplayState()
        controller.addListener { state = it }
        controller.setLyricsRequested(true)
        controller.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LyricsStatus.UNSUPPORTED, state.lyrics.status)
        assertEquals(emptyList<LyricLine>(), state.lyrics.lines)
        controller.release()
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
        val player = PlayerSnapshot.Empty.copy(title = "歌曲", durationMs = 2_000)
        val data = TestPlayerDataSource(player)
        val controller = EnhancedPlayerController(PlayerSession("Other player", data, PlayerActions({}, {}, {}, {}, {}), lyrics = provider))
        var state = PlayerDisplayState()
        controller.addListener { state = it }
        controller.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, receivedPlayer)
        controller.setLyricsRequested(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(expected, state.lyrics)
        assertSame(player, receivedPlayer)
        controller.setLyricsRequested(false)
        data.publish(player.copy(title = "Next"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<LyricLine>(), state.lyrics.lines)
        controller.release()
        assertEquals(1, releaseCount)
    }
}
