package com.jaco.musicenhance.player

import android.os.Looper
import com.jaco.musicenhance.adapter.NativeFavoriteControl
import com.jaco.musicenhance.adapter.nativePlayerSession
import com.jaco.musicenhance.player.artwork.ArtworkProvider
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerDisplayState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EnhancedPlayerControllerTest {
    @Test fun nativeSessionReleasesFavoriteWorkerOnceOnScreenClose() {
        var releases = 0
        val favorite = object : NativeFavoriteControl {
            override fun read(player: PlayerSnapshot) = PlayerControlState()
            override fun toggle(player: PlayerSnapshot) = false
            override fun release() { releases++ }
        }
        val session = nativePlayerSession("Test", LyricsProvider.Unsupported, ArtworkProvider.Unsupported,
            readArtwork = { null }, repeatApi = { error("Unused repeat provider") }, favorite = favorite)
        val player = EnhancedPlayerController(session)
        player.release()
        player.release()
        assertEquals(1, releases)
    }

    @Test fun callbacksUseCurrentAdapterStateAndDoNotInventFavoriteSuccess() {
        val data = TestPlayerDataSource(PlayerSnapshot.Empty.copy(title = "First", durationMs = 10_000))
        var allowed = false
        val events = mutableListOf<String>()
        val actions = PlayerActions(
            { events += "play:${data.song.title}" }, { events += "previous" }, { events += "next" },
            { events += "seek:$it" }, { events += "seekPlay:$it" },
            cycleRepeat = { events += "repeat"; allowed }, toggleFavorite = { allowed },
        )
        val player = EnhancedPlayerController(PlayerSession("Unrelated player", data, actions))
        var delivered = PlayerDisplayState()
        player.addListener { delivered = it }
        player.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.toggleFavorite())
        assertNull(delivered.controls.favorite)
        data.publish(data.song.copy(title = "Second"))
        allowed = true
        player.playPause(); player.previous(); player.next()
        player.seekTo(1234, data.song.metadataKey); player.seekAndPlay(5678, data.song.metadataKey)
        assertTrue(player.cycleRepeat())
        assertTrue(player.toggleFavorite())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(delivered.controls.favorite)
        assertEquals(listOf("play:Second", "previous", "next", "seek:1234", "seekPlay:5678", "repeat"), events)
        assertEquals("Second", delivered.title)
        player.release()
    }

    @Test fun staleSeekAndFavoriteCannotApplyToTheNextTrack() {
        val old = PlayerSnapshot.Empty.copy(title = "First", durationMs = 10_000)
        val data = TestPlayerDataSource(old)
        val seeks = mutableListOf<Long>()
        val player = EnhancedPlayerController(PlayerSession("Test", data, PlayerActions({}, {}, {}, seeks::add, seeks::add)))
        var state = PlayerDisplayState()
        player.addListener { state = it }
        player.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        data.publish(old.copy(title = "Next", controls = PlayerControlState(favorite = true, songTitle = "First", favoritePending = true)))
        player.seekAndPlay(5_000, old.metadataKey)
        player.seekTo(5_000, old.metadataKey)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seeks.isEmpty())
        assertNull(state.controls.favorite)
        assertFalse(state.controls.favoritePending)
        player.seekAndPlay(15_000, data.song.metadataKey)
        assertEquals(listOf(10_000L), seeks)
        player.release()
    }

    @Test fun queuedUpdatesUseLatestSongAndVisibilityStopsPollingAndLateNotifications() {
        val data = TestPlayerDataSource()
        val player = EnhancedPlayerController(PlayerSession("Test", data, PlayerActions({}, {}, {}, {}, {})))
        val received = mutableListOf<PlayerDisplayState>()
        player.addListener(received::add)
        player.setActive(true)
        player.setActive(true)
        val callback = data.listeners.single()
        data.publish(data.song.copy(title = "Skipped"))
        data.publish(data.song.copy(title = "Current", positionMs = 50))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Current", received.last().title)
        assertFalse(received.any { it.title == "Skipped" })
        player.setActive(false)
        assertTrue(data.listeners.isEmpty())
        val reads = data.snapshotReads
        val deliveries = received.size
        callback(data.song)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(reads, data.snapshotReads)
        assertEquals(deliveries, received.size)
        data.song = data.song.copy(positionMs = 1_000)
        player.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1_000L, received.last().positionMs)
        player.release()
        val releasedDeliveries = received.size
        callback(data.song)
        player.setActive(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(data.listeners.isEmpty())
        assertEquals(releasedDeliveries, received.size)
    }

    @Test fun cleanupReleasesEveryProviderEvenIfNativeReleaseFails() {
        var artworkReleased = false
        var lyricsReleased = false
        val data = object : PlayerDataSource {
            override fun snapshot() = PlayerSnapshot.Empty
            override fun addListener(listener: (PlayerSnapshot) -> Unit) = Unit
            override fun removeListener(listener: (PlayerSnapshot) -> Unit) = Unit
            override fun bassLevel() = 0f
        }
        val session = PlayerSession("Test", data, PlayerActions({}, {}, {}, {}, {}),
            object : LyricsProvider by LyricsProvider.Unsupported { override fun release() { lyricsReleased = true } },
            object : ArtworkProvider by ArtworkProvider.Unsupported { override fun release() { artworkReleased = true } },
            onRelease = { error("Native cleanup failed") },
        )
        val player = EnhancedPlayerController(session)
        assertTrue(runCatching { player.release() }.isFailure)
        assertTrue(lyricsReleased)
        assertTrue(artworkReleased)
        player.release()
    }
}
