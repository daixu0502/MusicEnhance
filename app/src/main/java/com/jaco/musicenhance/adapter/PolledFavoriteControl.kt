package com.jaco.musicenhance.adapter

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns favorite reads and pending actions; never infers success from a click or flips state locally. */
internal class PolledFavoriteControl(
    createSource: () -> Source,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-native-favorite").apply { isDaemon = true }
    },
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : NativeFavoriteControl {
    data class State(val trackKey: String, val favorite: Boolean)
    interface Source {
        fun read(player: PlayerSnapshot): State?
        fun toggle(state: State): Boolean
    }
    private class Request(val player: PlayerSnapshot) {
        var state: State? = null
        var nextReadAtMs = 0L
        var mutationPending = false
        var expectedFavorite: Boolean? = null
        var mutationExpiresAtMs = Long.MAX_VALUE
        var failureReported = false
    }

    private val source by lazy(createSource)
    @Volatile private var released = false
    @Volatile private var request: Request? = null
    private var readPending = false

    private fun requestFor(player: PlayerSnapshot): Request = request?.takeIf {
        it.player.metadataKey == player.metadataKey
    } ?: Request(player).also { request = it }

    override fun read(player: PlayerSnapshot): PlayerControlState {
        if (released) return PlayerControlState()
        val current = requestFor(player)
        if (current.mutationPending && nowMs() >= current.mutationExpiresAtMs) {
            current.mutationPending = false
            current.expectedFavorite = null
            moduleInfo("Native favorite: confirmation timeout; retaining native state")
        }
        if (!readPending && nowMs() >= current.nextReadAtMs) readNativeState(current)
        return PlayerControlState(favorite = current.state?.favorite, songTitle = player.title)
    }

    private fun readNativeState(current: Request) {
        readPending = true
        worker.execute {
            if (released) return@execute
            val result = runCatching { source.read(current.player) }
            mainHandler.post {
                readPending = false
                if (released || request !== current) return@post
                current.state = result.getOrNull()
                if (current.state?.favorite == current.expectedFavorite && current.expectedFavorite != null) {
                    current.mutationPending = false
                    current.expectedFavorite = null
                    moduleInfo("Native favorite: native state confirmed")
                }
                reportFailure(current, result.exceptionOrNull())
                current.nextReadAtMs = nowMs() + if (current.mutationPending) CONFIRM_POLL_MS else STATE_POLL_MS
            }
        }
    }

    override fun toggle(player: PlayerSnapshot): Boolean {
        if (released) return false
        val current = requestFor(player)
        val displayed = current.state ?: return false
        if (current.mutationPending) return false
        current.mutationPending = true
        // Re-read before dispatching: host-side favorites may have changed since the last poll.
        worker.execute {
            if (released || request !== current) return@execute
            val result = runCatching { source.read(player) }
            mainHandler.post {
                if (released || request !== current) return@post
                val fresh = result.getOrNull()?.takeIf { it.trackKey == displayed.trackKey }
                val dispatched = runCatching { fresh != null && source.toggle(fresh) }
                if (dispatched.getOrDefault(false)) {
                    current.expectedFavorite = !fresh!!.favorite
                    current.mutationExpiresAtMs = nowMs() + CONFIRM_TIMEOUT_MS
                    moduleInfo("Native favorite: native action dispatched")
                } else {
                    current.mutationPending = false
                    moduleInfo("Native favorite: native action unavailable or song changed")
                }
                reportFailure(current, result.exceptionOrNull() ?: dispatched.exceptionOrNull())
                current.nextReadAtMs = 0L
            }
        }
        return true
    }

    private fun reportFailure(current: Request, error: Throwable?) {
        if (error == null || current.failureReported) return
        current.failureReported = true
        moduleInfo("Native favorite failed: ${error.cause ?: error}")
    }

    override fun release() {
        if (released) return
        released = true
        request = null
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private companion object {
        const val STATE_POLL_MS = 500L
        const val CONFIRM_POLL_MS = 200L
        const val CONFIRM_TIMEOUT_MS = 4_000L
    }
}
