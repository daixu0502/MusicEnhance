package com.jaco.musicenhance.adapter.salt

import android.graphics.Bitmap
import android.os.Looper
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SaltPlaybackSourceTest {
    private val player = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist", album = "Album", durationMs = 100_000)
    private lateinit var source: SaltPlaybackSource
    private lateinit var lyrics: SaltLyricsSource

    @Before fun setUp() {
        Host.reset()
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                "com.salt.music.service.MusicController" -> Host::class.java
                "com.salt.music.data.entry.Song" -> Song::class.java
                "androidx.media3.l14" -> Flow::class.java
                "androidx.media3.eh2" -> Cover::class.java
                "androidx.media3.jd3" -> Host::class.java
                "androidx.media3.uc1" -> Document::class.java
                "androidx.media3.ed1" -> Line::class.java
                else -> super.loadClass(name)
            }
        }
        source = SaltPlaybackSource(loader)
        lyrics = SaltLyricsSource(loader, source)
    }

    @After fun tearDown() { source.release(); drain() }

    @Test fun pausedSeekExplicitlyResumesAndPlayingSeekNeverToggles() {
        source.seekTo(20_000, player.copy(isPlaying = true), resume = true)
        drain()
        assertEquals(listOf("seek:20000", "play"), Host.calls)
        source.seekTo(30_000, player.copy(isPlaying = false), resume = true)
        drain()
        assertEquals(listOf("seek:20000", "play", "seek:30000"), Host.calls)
    }

    @Test fun seekingProgressDoesNotResumeAndEndDoesNotSkipToNextSong() {
        source.seekTo(Long.MAX_VALUE, player, resume = false)
        drain()
        assertEquals(listOf("seek:99999"), Host.calls)
        assertFalse(Host.playing)
    }

    @Test fun rapidClicksAndTrackChangeRejectStaleRequests() {
        source.seekTo(10_000, player, true)
        source.seekTo(20_000, player, true)
        drain()
        assertEquals(listOf("seek:20000", "play"), Host.calls)
        Host.calls.clear()
        source.seekTo(30_000, player, true)
        Host.`ޠ`.value = Song(id = "another-recording")
        drain()
        assertTrue(Host.calls.isEmpty())
    }

    @Test fun failedSeekOrTrackChangeDuringSeekNeverResumes() {
        Host.afterSeek = { error("seek failed") }
        source.seekTo(10_000, player, true)
        drain()
        assertEquals(listOf("seek:10000"), Host.calls)
        Host.afterSeek = { Host.`ޠ`.value = Song(id = "new") }
        source.seekTo(20_000, player, true)
        drain()
        assertEquals(listOf("seek:10000", "seek:20000"), Host.calls)
    }

    @Test fun releaseCancelsQueuedWorkAndResumeAfterSeek() {
        Host.afterSeek = source::release
        source.seekTo(10_000, player, true)
        drain()
        source.next()
        source.seekTo(20_000, player, true)
        drain()
        assertEquals(listOf("seek:10000"), Host.calls)
    }

    @Test fun releaseBeforeQueueDrainCancelsAllOperations() {
        source.seekTo(10_000, player, true)
        source.next()
        source.release()
        drain()
        assertTrue(Host.calls.isEmpty())
    }

    @Test fun coverMustBeRealAndBelongToMatchingSong() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Host.`ࡠ`.value = Cover(false, bitmap)
        assertNull(source.artwork(player))
        Host.`ࡠ`.value = Cover(true, bitmap)
        assertSame(bitmap, source.artwork(player))
        assertNull(source.artwork(player.copy(title = "Next")))
        bitmap.recycle()
        assertNull(source.artwork(player))
    }

    @Test fun lyricsUseNativeIdentityAndRefreshWhenHostReplacesDocument() {
        Host.`ޓ`.value = "song"
        Host.`ޑ`.value = Document(listOf(Line(5_000, "First"), Line(10_000, "Second")))
        assertEquals(listOf(LyricLine(5_000, "First"), LyricLine(10_000, "Second")), lyrics.readLyrics(player)?.lines)
        Host.`ޠ`.value = Song(id = "same-metadata-new-recording")
        assertNull(lyrics.readLyrics(player))
        Host.`ޓ`.value = "same-metadata-new-recording"
        Host.`ޑ`.value = Document(listOf(Line(9_000, "New lyrics")))
        assertEquals(listOf(LyricLine(9_000, "New lyrics")), lyrics.readLyrics(player)?.lines)
    }

    @Test fun cueOffsetAndInvalidLinesAreHandledWithoutInventingTimes() {
        Host.`ޓ`.value = "song"
        Host.`ࡨ` = 1_000
        Host.`ޑ`.value = Document(listOf(Line(500, "Before cue"), Line(5_000, "  "),
            Line(3_000, "Second"), Line(1_000, "First"), Line(3_000, "Second"), Line(101_000, "Beyond cue")))
        assertEquals(listOf(LyricLine(0, "First"), LyricLine(2_000, "Second")), lyrics.readLyrics(player)?.lines)
    }

    @Test fun nativeModesHaveNoInventedFallback() {
        assertEquals(RepeatMode.LIST_LOOP, source.repeatMode())
        assertEquals(RepeatMode.SINGLE_LOOP, SaltPlaybackSource.decodeRepeat("REPEAT_ONE"))
        assertEquals(RepeatMode.SHUFFLE, SaltPlaybackSource.decodeRepeat("RANDOM"))
        assertEquals(RepeatMode.UNKNOWN, SaltPlaybackSource.decodeRepeat(null))
        assertEquals(RepeatMode.UNKNOWN, SaltPlaybackSource.decodeRepeat("new-mode"))
    }

    @Test fun fileWithoutArtistTagStillSupportsSeekingButAnotherKnownArtistIsRejected() {
        Host.`ޠ`.value = Song(artist = "")
        source.seekTo(20_000, player.copy(artist = "未知歌手"), true)
        drain()
        assertEquals(listOf("seek:20000", "play"), Host.calls)
        Host.calls.clear()
        source.seekTo(30_000, player.copy(artist = "Other artist"), true)
        drain()
        assertTrue(Host.calls.isEmpty())
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    // The fixture deliberately preserves the APK's Unicode member names to exercise reflection.
    class Song(val id: String = "song", val title: String = "Song", val artist: String = "Artist",
               val album: String = "Album", val duration: Long = 100_000)
    class Flow(var value: Any?)
    class Cover(@JvmField val `Ԯ`: Boolean, @JvmField val `ԯ`: Bitmap)
    class Document(@JvmField val `Ԩ`: List<Line>)
    class Line(@JvmField val `Ϳ`: Long, @JvmField val `ԫ`: String)
    enum class Mode { CIRCLE }
    class Playback {
        fun `Ԯ`(positionMs: Long) { Host.calls += "seek:$positionMs"; Host.afterSeek() }
    }
    class Host {
        companion object {
            @JvmField var `ޠ` = Flow(Song())
            @JvmField var `ࡠ` = Flow(null)
            @JvmField var `ޑ` = Flow(null)
            @JvmField var `ޓ` = Flow(null)
            @JvmField var `ޤ` = Flow(Mode.CIRCLE)
            @JvmField var `ދ` = Playback()
            @JvmField var `ࡨ` = 0L
            var playing = false
            val calls = mutableListOf<String>()
            var afterSeek: () -> Unit = {}
            @JvmStatic fun `ތ`() = playing
            @JvmStatic fun `ޔ`() { calls += "play"; playing = true }
            @JvmStatic fun `ޒ`() { calls += "pause"; playing = false }
            @JvmStatic fun `ޘ`() { calls += "next" }
            @JvmStatic fun `ޚ`() { calls += "previous" }
            @JvmStatic fun `ހ`() { calls += "repeat" }
            fun reset() {
                `ޠ` = Flow(Song()); `ࡠ` = Flow(null); `ޑ` = Flow(null)
                `ޓ` = Flow(null); `ޤ` = Flow(Mode.CIRCLE); `ࡨ` = 0L
                playing = false; calls.clear(); afterSeek = {}
            }
        }
    }
}
