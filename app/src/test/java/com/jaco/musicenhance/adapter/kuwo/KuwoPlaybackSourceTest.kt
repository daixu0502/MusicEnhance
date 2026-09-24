package com.jaco.musicenhance.adapter.kuwo

import android.os.Looper
import com.jaco.musicenhance.player.model.PlayerSnapshot
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
class KuwoPlaybackSourceTest {
    private val player = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist", durationMs = 180_000)
    private lateinit var source: KuwoPlaybackSource
    private lateinit var control: Control

    @Before fun setUp() {
        control = Control()
        Modules.control = control
        source = KuwoPlaybackSource(object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                "q1.b" -> Modules::class.java
                "cn.kuwo.mod.playcontrol.c" -> Control::class.java
                "cn.kuwo.base.bean.Music" -> Music::class.java
                else -> super.loadClass(name)
            }
        })
    }

    @After fun tearDown() { source.release(); drain() }

    @Test fun pausedLyricsSeekThenResumeDespiteStalePlayingSnapshot() {
        source.seekAndPlay(42_000, player.copy(isPlaying = true))
        assertTrue(control.calls.isEmpty())
        drain()
        assertEquals(listOf("seek:42000", "resume"), control.calls)
        assertEquals(Status.PLAYING, control.playbackStatus)
    }

    @Test fun playingLyricsSeekDoesNotToggleOrRestartPlayback() {
        control.playbackStatus = Status.PLAYING
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000"), control.calls)
        assertEquals(Status.PLAYING, control.playbackStatus)
    }

    @Test fun seekThatAlreadyResumesPlaybackDoesNotResumeAgain() {
        control.afterSeek = { control.playbackStatus = Status.PLAYING }
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000"), control.calls)
    }

    @Test fun queuedActionRejectsAnotherTitleOrArtist() {
        control.music = Music(name = "Next song")
        source.seekAndPlay(24_000, player)
        drain()
        control.music = Music(artist = "Another artist")
        source.seekAndPlay(24_000, player)
        drain()
        assertTrue(control.calls.isEmpty())
    }

    @Test fun songIdentityChangeDuringSeekCannotResumeAnotherRecording() {
        control.afterSeek = { control.music = Music(rid = 2) }
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000"), control.calls)
        assertEquals(Status.PAUSE, control.playbackStatus)
    }

    @Test fun replacedNativeControlCannotReceiveResumeFromAnOldSeek() {
        val replacement = Control()
        control.afterSeek = { Modules.control = replacement }
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000"), control.calls)
        assertTrue(replacement.calls.isEmpty())
    }

    @Test fun failedSeekCannotResumePlayback() {
        control.afterSeek = { error("Native seek failed") }
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000"), control.calls)
        assertEquals(Status.PAUSE, control.playbackStatus)
    }

    @Test fun rapidClicksKeepOnlyLatestTargetAndClampToDuration() {
        source.seekAndPlay(12_000, player)
        source.seekAndPlay(24_000, player)
        source.seekAndPlay(Long.MAX_VALUE, player)
        drain()
        assertEquals(listOf("seek:180000", "resume"), control.calls)
        source.seekAndPlay(-1, player)
        drain()
        assertEquals("seek:0", control.calls.last())
    }

    @Test fun targetFitsTheNativeIntegerMillisecondsWithoutOverflow() {
        source.seekAndPlay(Long.MAX_VALUE, player.copy(durationMs = Long.MAX_VALUE))
        drain()
        assertEquals("seek:${Int.MAX_VALUE}", control.calls.first())
    }

    @Test fun releaseCancelsQueuedActionsAndRejectsFurtherClicks() {
        source.seekAndPlay(24_000, player)
        source.release()
        source.seekAndPlay(30_000, player)
        drain()
        assertTrue(control.calls.isEmpty())
    }

    @Test fun releaseDuringSeekPreventsResume() {
        control.afterSeek = source::release
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000"), control.calls)
    }

    @Test fun reentrantNewRequestSupersedesTheOldResume() {
        control.afterSeek = {
            control.afterSeek = {}
            source.seekAndPlay(48_000, player)
        }
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000", "seek:48000", "resume"), control.calls)
    }

    @Test fun missingNativeDataOrDurationDoesNotInvokePlayback() {
        Modules.control = null
        source.seekAndPlay(24_000, player)
        drain()
        Modules.control = control
        control.music = null
        source.seekAndPlay(24_000, player)
        drain()
        control.music = Music()
        source.seekAndPlay(24_000, player.copy(durationMs = 0))
        drain()
        assertTrue(control.calls.isEmpty())
    }

    @Test fun nativeResumeRefusalDoesNotRetryOrTogglePlayback() {
        control.resumeAccepted = false
        source.seekAndPlay(24_000, player)
        drain()
        assertEquals(listOf("seek:24000", "resume"), control.calls)
        assertEquals(Status.PAUSE, control.playbackStatus)
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    // These names and signatures mirror Kuwo 12.2.2.4 and are called by reflection.
    @Suppress("unused")
    object Modules {
        var control: Control? = null
        @JvmStatic fun e0() = control
    }

    @Suppress("unused")
    class Music(@JvmField val rid: Long = 1, @JvmField val name: String = "Song", @JvmField val artist: String = "Artist")
    enum class Status { PAUSE, PLAYING }

    @Suppress("unused")
    class Control {
        var music: Music? = Music()
        var playbackStatus = Status.PAUSE
        var resumeAccepted = true
        var afterSeek: () -> Unit = {}
        val calls = mutableListOf<String>()
        fun X() = music
        fun getStatus() = playbackStatus
        fun seek(positionMs: Int) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            calls += "seek:$positionMs"
            afterSeek()
        }
        fun continuePlay(): Boolean {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            calls += "resume"
            if (resumeAccepted) playbackStatus = Status.PLAYING
            return resumeAccepted
        }
    }
}
