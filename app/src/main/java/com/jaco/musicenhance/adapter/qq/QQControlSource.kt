package com.jaco.musicenhance.adapter.qq

import android.os.Handler
import android.os.HandlerThread
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerControlState
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** QQ Music 20.8.5.8's own service and collection store. Never opens a mode popup. */
internal class QQControlSource(private val classLoader: ClassLoader) {
    @Volatile private var state = PlayerControlState()
    private val refreshing = AtomicBoolean()
    private val changingMode = AtomicBoolean()
    private var lastFailure = ""

    private val api by lazy { Api(classLoader) }

    fun controlState(): PlayerControlState {
        refresh()
        return state
    }

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        worker.post {
            try {
                readState()
            } catch (error: Throwable) {
                reportFailure(error)
            } finally {
                refreshing.set(false)
            }
        }
    }

    fun cycleRepeat(): Boolean {
        // Serialize clicks so a slow service response cannot queue several stale mode changes.
        if (!changingMode.compareAndSet(false, true)) return true
        worker.post {
            try {
                val playEnvironment = api.getPlayEnvironment.invoke(null)
                val current = api.getPlayMode.invoke(playEnvironment) as Int
                val target = QQRepeatModes.next(current)
                val service = api.playerService.get(null)
                if (service != null) {
                    val accepted = api.setPlayMode.invoke(service, target, api.modeChangeSource) == true
                    moduleInfo("QQ direct repeat: $current -> $target, accepted=$accepted")
                    if (accepted) {
                        state = state.copy(repeatMode = QQRepeatModes.decode(target))
                    }
                }
                readState()
            } catch (error: Throwable) {
                reportFailure(error)
            } finally {
                changingMode.set(false)
            }
        }
        return true
    }

    private fun readState() {
        val playEnvironment = api.getPlayEnvironment.invoke(null)
        val mode = QQRepeatModes.decode(api.getPlayMode.invoke(playEnvironment) as Int)
        val song = api.getPlaySong.invoke(playEnvironment)
        val songId = song?.let { api.songId.invoke(it) as Long }
        val songTitle = song?.let { api.songTitle.invoke(it) as? String }
        val manager = api.userDataGet.invoke(null)
        // An uninitialized collection cache is unknown, not "not liked".
        val ready = api.isLikeDataInit.invoke(manager) == true
        val favorite = if (song != null && ready) api.isILike.invoke(manager, song) as Boolean else null
        val currentSong = api.getPlaySong.invoke(playEnvironment)
        val currentId = currentSong?.let { api.songId.invoke(it) as Long }
        if (currentId != songId) return
        state = PlayerControlState(mode, favorite, songTitle)
    }

    private fun reportFailure(error: Throwable) {
        val cause = error.cause ?: error
        val message = "${cause.javaClass.simpleName}: ${cause.message}"
        if (message != lastFailure) {
            lastFailure = message
            moduleInfo("QQ direct controls unavailable: $message")
        }
    }

    private class Api(private val classLoader: ClassLoader) {
        private fun type(name: String) = Class.forName(name, false, classLoader)
        private val ipc = type("com.tencent.qqmusic.common.ipc.IPlayProcessMethods")
        private val song = type("com.tencent.qqmusicplayerprocess.songinfo.SongInfo")
        private val users = type("com.tencent.qqmusic.business.userdata.UserDataManager")
        private val service = type("com.tencent.qqmusicplayerprocess.servicenew.IQQPlayerServiceNew")
        val getPlayEnvironment = type("com.tencent.qqmusic.common.ipc.MusicProcess").getMethod("playEnv")
        val getPlayMode = ipc.getMethod("getPlayMode")
        val getPlaySong = ipc.getMethod("getPlaySong")
        val songId = song.getMethod("C3")
        val songTitle = song.getMethod("j3")
        val userDataGet = users.getMethod("get")
        val isILike = users.getMethod("isILike", song)
        val isLikeDataInit = users.getMethod("isILikeDataInit")
        val playerService = type("com.tencent.qqmusicplayerprocess.servicenew.k").declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == service
        }.apply { isAccessible = true }
        val setPlayMode = service.getMethod("setPlayMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val from = type("com.tencent.qqmusicplayerprocess.servicenew.FromInfo")
        val modeChangeSource = from.getField("value").getInt(from.getField("FROM_PLAY_MODE_POPUP_WINDOW").get(null))
    }

    companion object {
        // QQ's IPC and collection lookups must not block drawing or touch dispatch.
        private val worker = Handler(HandlerThread("MusicEnhance-controls").apply { start() }.looper)
    }
}
