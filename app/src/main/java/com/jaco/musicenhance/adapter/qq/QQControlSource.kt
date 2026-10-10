package com.jaco.musicenhance.adapter.qq

import android.os.Handler
import android.os.HandlerThread
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.atomic.AtomicBoolean

/** QQ Music 20.8.5.8 / 20.9.0.8 service and collection store. Never opens a mode popup. */
internal class QQControlSource(private val classLoader: ClassLoader) {
    @Volatile private var state = PlayerControlState()
    private val refreshing = AtomicBoolean()
    private val changingMode = AtomicBoolean()
    private val changingFavorite = AtomicBoolean()
    @Volatile private var released = false
    private var lastFailure = ""

    private val api by lazy { Api(classLoader) }

    fun controlState(): PlayerControlState {
        refresh()
        return state
    }

    fun refresh() {
        if (released) return
        if (!refreshing.compareAndSet(false, true)) return
        val queued = worker.post {
            try {
                if (released) return@post
                readState()
            } catch (error: Throwable) {
                reportFailure(error)
            } finally {
                refreshing.set(false)
            }
        }
        if (!queued) refreshing.set(false)
    }

    fun cycleRepeat(): Boolean {
        if (released) return false
        // Serialize clicks so a slow service response cannot queue several stale mode changes.
        if (!changingMode.compareAndSet(false, true)) return true
        val queued = worker.post {
            try {
                if (released) return@post
                val playEnvironment = api.getPlayEnvironment.invoke(null)
                val current = api.getPlayMode.invoke(playEnvironment) as Int
                val target = QQRepeatModes.next(current)
                val service = api.playerService.get(null)
                if (service != null) {
                    val accepted = api.setPlayMode.invoke(service, target, api.modeChangeSource) == true
                    moduleInfo("QQ direct repeat: $current -> $target, accepted=$accepted")
                    if (accepted && !released) {
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
        if (!queued) changingMode.set(false)
        return queued
    }

    /** Same collection manager used by QQ's widget; keeps host validation and cloud synchronization. */
    fun toggleFavorite(player: PlayerSnapshot): Boolean {
        if (released || !changingFavorite.compareAndSet(false, true)) return false
        val queued = worker.post {
            try {
                if (released) return@post
                val environment = api.getPlayEnvironment.invoke(null)
                val song = api.getPlaySong.invoke(environment) ?: return@post
                if ((api.songTitle.invoke(song) as? String)?.trim() != player.title.trim()) return@post
                val id = api.songId.invoke(song)
                val manager = api.userDataGet.invoke(null)
                if (api.isLikeDataInit.invoke(manager) != true || released) return@post
                val favorite = api.isILike.invoke(manager, song) == true
                if (api.getPlaySong.invoke(environment)?.let { api.songId.invoke(it) } != id) return@post
                val accepted = if (favorite) api.removeFavorite.invoke(manager, song) == true
                    else api.addFavorite.invoke(manager, song) == 0
                moduleInfo("QQ collection action: favorite=$favorite, accepted=$accepted")
                if (!released) readState()
            } catch (error: Throwable) {
                reportFailure(error)
            } finally { changingFavorite.set(false) }
        }
        if (!queued) changingFavorite.set(false)
        return queued
    }

    fun release() { released = true }

    private fun readState() {
        if (released) return
        val playEnvironment = api.getPlayEnvironment.invoke(null)
        val mode = QQRepeatModes.decode(api.getPlayMode.invoke(playEnvironment) as Int)
        val next = runCatching {
            val song = api.getPlaySong.invoke(playEnvironment)
            val songId = song?.let { api.songId.invoke(it) as Long }
            val songTitle = song?.let { api.songTitle.invoke(it) as? String }
            val manager = api.userDataGet.invoke(null)
            // An uninitialized collection cache is unknown, not "not liked".
            val ready = api.isLikeDataInit.invoke(manager) == true
            val favorite = if (song != null && ready) api.isILike.invoke(manager, song) as Boolean else null
            val currentSong = api.getPlaySong.invoke(playEnvironment)
            val currentId = currentSong?.let { api.songId.invoke(it) as Long }
            if (currentId != songId) null else PlayerControlState(mode, favorite, songTitle)
        }.getOrElse { error ->
            // A missing optional collection API must not hide the independent native repeat mode.
            reportFailure(error)
            PlayerControlState(repeatMode = mode)
        }
        if (next != null && !released) state = next
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
        private val process = QQPlayProcessApi(classLoader)
        private val ipc = process.type
        private val song = type("com.tencent.qqmusicplayerprocess.songinfo.SongInfo")
        private val users by lazy { QQInterfaceRules.users(classLoader, song) }
        private val service = type("com.tencent.qqmusicplayerprocess.servicenew.IQQPlayerServiceNew")
        val getPlayEnvironment = process.getEnvironment
        val getPlayMode = ipc.getMethod("getPlayMode")
        val getPlaySong = ipc.getMethod("getPlaySong")
        val songId by lazy { QQInterfaceRules.songId(classLoader, song) }
        val songTitle by lazy { QQInterfaceRules.songTitle(classLoader, song) }
        val userDataGet by lazy { users.getMethod("get") }
        val isILike by lazy { users.getMethod("isILike", song) }
        val isLikeDataInit by lazy { users.getMethod("isILikeDataInit") }
        val addFavorite by lazy { users.getMethod("addToILike", song) }
        val removeFavorite by lazy { users.getMethod("deleteFromILike", song) }
        val playerService by lazy { QQInterfaceRules.playerService(classLoader, service) }
        val setPlayMode = service.getMethod("setPlayMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val from = type("com.tencent.qqmusicplayerprocess.servicenew.FromInfo")
        val modeChangeSource = from.getField("value").getInt(from.getField("FROM_PLAY_MODE_POPUP_WINDOW").get(null))
    }

    companion object {
        // QQ's IPC and collection lookups must not block drawing or touch dispatch.
        private val worker = Handler(HandlerThread("MusicEnhance-controls").apply { start() }.looper)
    }
}
