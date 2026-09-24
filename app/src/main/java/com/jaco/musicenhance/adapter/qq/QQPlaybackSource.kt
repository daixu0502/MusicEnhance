package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** QQ 20.8.5.8's lyric-page controls, independent of the MediaSession command queue. */
internal class QQPlaybackSource(
    loader: ClassLoader,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MusicEnhance-qq-seek").apply { isDaemon = true }
    },
) {
    private val api by lazy { Api(loader) }
    private val generation = AtomicLong()
    @Volatile private var released = false

    @Synchronized
    fun seekAndPlay(positionMs: Long, player: PlayerSnapshot) {
        if (released || player.durationMs <= 0) return
        val request = generation.incrementAndGet()
        val targetMs = positionMs.coerceIn(0, player.durationMs)
        worker.execute {
            fun isCurrent() = !released && generation.get() == request
            if (!isCurrent()) return@execute
            runCatching {
                val native = api
                val song = native.song()?.takeIf { it.title == player.title.trim() } ?: return@runCatching
                fun canApply() = isCurrent() && native.song() == song
                if (!canApply()) return@runCatching
                val applySeek = Runnable {
                    runCatching {
                        if (!canApply()) return@Runnable
                        val actualPositionMs = native.seek(targetMs)
                        if (actualPositionMs < 0 || !canApply()) return@Runnable
                        // QQ's resume handles a pending pause and is harmless when already playing.
                        // Do not gate it on the delayed MediaSession isPlaying snapshot after a seek.
                        val result = native.resume()
                        moduleInfo("QQ lyric seek: targetMs=$targetMs, actualMs=$actualPositionMs, resumeResult=$result")
                    }.onFailure(::reportFailure)
                }
                if (native.needsStart()) {
                    // Native lyric clicks start an unloaded/stopped player first, then seek after
                    // the same preparation interval. A delayed request must still belong to this song.
                    if (!canApply()) return@runCatching
                    native.start()
                    synchronized(this) {
                        if (isCurrent()) worker.schedule(applySeek, START_SEEK_DELAY_MS, TimeUnit.MILLISECONDS)
                    }
                } else {
                    applySeek.run()
                }
            }.onFailure(::reportFailure)
        }
    }

    @Synchronized
    fun release() {
        released = true
        generation.incrementAndGet()
        worker.shutdownNow()
    }

    private data class Song(val id: Long, val title: String)

    private fun reportFailure(error: Throwable) = moduleInfo("QQ lyric seek failed: ${error.cause ?: error}")

    private class Api(loader: ClassLoader) {
        private val environmentType = loader.loadClass("com.tencent.qqmusic.common.ipc.IPlayProcessMethods")
        private val getEnvironment = loader.loadClass("com.tencent.qqmusic.common.ipc.MusicProcess").getMethod("playEnv")
        private val getSong = environmentType.getMethod("getPlaySong")
        private val getState = environmentType.getMethod("getPlayState")
        private val songType = loader.loadClass("com.tencent.qqmusicplayerprocess.songinfo.SongInfo")
        private val getSongId = songType.getMethod("C3")
        private val getSongTitle = songType.getMethod("j3")
        private val controlsType = loader.loadClass("com.tencent.qqmusic.common.player.g")
        private val controls = controlsType.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == controlsType
        }.apply { isAccessible = true }.get(null)
        private val seek = controlsType.getMethod("s", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val resume = controlsType.getMethod("r", Int::class.javaPrimitiveType)
        private val start = controlsType.getMethod("o", Int::class.javaPrimitiveType)
        private val fromType = loader.loadClass("com.tencent.qqmusicplayerprocess.servicenew.FromInfo")
        private val lyricSource = fromType.getField("value").getInt(fromType.getField("FROM_LYRIC").get(null))

        fun song(): Song? {
            val song = getSong.invoke(getEnvironment.invoke(null)) ?: return null
            val title = (getSongTitle.invoke(song) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Song((getSongId.invoke(song) as Number).toLong(), title)
        }

        // The facade selects QQ's active player implementation and retains its playback restrictions.
        fun seek(positionMs: Long) = (seek.invoke(controls, positionMs, lyricSource) as Number).toLong()
        fun resume() = (resume.invoke(controls, lyricSource) as Number).toInt()
        fun needsStart(): Boolean {
            // PlayStateHelper.e: 0 = unloaded, 601 = stopped. Neither can be resumed directly.
            val state = (getState.invoke(getEnvironment.invoke(null)) as Number).toInt()
            return state == 0 || state == 601
        }
        fun start() { start.invoke(controls, lyricSource) }
    }

    private companion object {
        const val START_SEEK_DELAY_MS = 500L
    }
}
