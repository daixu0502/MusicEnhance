package com.jaco.musicenhance.adapter.apple

import android.app.Application
import android.os.SystemClock
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.WeakHashMap
import java.lang.reflect.Executable
import io.github.libxposed.api.XposedInterface

/** Owns a native lyrics ViewModel so loading doesn't require opening Apple's lyrics tab. */
internal class AppleLyricsProvider(
    private val application: Application,
    private val currentItem: (PlayerSnapshot) -> Any?,
) : LyricsProvider {
    private val api by lazy { AppleLyricsApi(application.classLoader) }
    private var viewModel: Any? = null
    private var expectedKey = ""
    private var metadataKey = ""
    private var nextCheckAtMs = 0L
    private var requestStartedAtMs = 0L

    override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
        val nowMs = SystemClock.elapsedRealtime()
        if (metadataKey != player.metadataKey) {
            clearViewModel()
            expectedKey = ""
            metadataKey = player.metadataKey
            nextCheckAtMs = 0
        }
        if (nowMs >= nextCheckAtMs) {
            nextCheckAtMs = nowMs + 500
            runCatching {
                val item = currentItem(player) ?: return@runCatching
                val id = item.javaClass.getMethod("getId").invoke(item)?.toString() ?: return@runCatching
                val queueId = (item.javaClass.getMethod("getQueueId").invoke(item) as Number).toLong()
                val key = "$id:$queueId\u0000${player.metadataKey}"
                val status = viewModel?.let { synchronized(requests) { requests[it]?.snapshot?.status } }
                if (key != expectedKey || (status != LyricsStatus.READY && nowMs - requestStartedAtMs >= RETRY_INTERVAL_MS)) {
                    clearViewModel()
                    val type = api.type
                    val model = type.getConstructor(Application::class.java).newInstance(application).also { viewModel = it }
                    synchronized(requests) { requests[model] = Request(id, queueId, LyricsSnapshot(key, LyricsStatus.LOADING)) }
                    expectedKey = key
                    requestStartedAtMs = nowMs
                    api.load.invoke(model, item)
                }
            }.onFailure {
                expectedKey = ""
                nextCheckAtMs = nowMs + RETRY_INTERVAL_MS
                moduleInfo("Apple Music lyrics request failed: ${it.cause ?: it}")
            }
        }
        val cached = viewModel?.let { synchronized(requests) { requests[it]?.snapshot } }
        if (cached == null || cached.trackKey != expectedKey || expectedKey.isBlank()) {
            return LyricsSnapshot(player.metadataKey, LyricsStatus.UNAVAILABLE)
        }
        return if (cached.status == LyricsStatus.LOADING && nowMs - requestStartedAtMs > 15_000) {
            cached.copy(status = LyricsStatus.UNAVAILABLE)
        } else cached
    }

    override fun release() {
        clearViewModel()
        expectedKey = ""
        metadataKey = ""
        nextCheckAtMs = 0
    }

    private fun clearViewModel() {
        viewModel?.let { model ->
            synchronized(requests) { requests.remove(model) }
            // ViewModel.clear also cancels its coroutine scope. onCleared alone does not.
            runCatching { model.javaClass.getMethod("clear\$lifecycle_viewmodel").invoke(model) }
        }
        viewModel = null
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 30_000L
        private data class Request(val id: String, val queueId: Long, val snapshot: LyricsSnapshot)
        private val requests = WeakHashMap<Any, Request>()
        private val installed = mutableSetOf<Executable>()

        fun install(loader: ClassLoader) = safeHook("Apple Music timed lyrics") {
            val method = AppleLyricsApi(loader).publish
            hook(method, "musicenhance.apple.lyrics") { chain ->
                val result = chain.proceed()
                val model = chain.thisObject
                val request = synchronized(requests) { requests[model] }
                if (request != null) safeHook("Apple Music lyric copy") {
                    val pointer = chain.args.firstOrNull()
                    if (pointer == null) {
                        synchronized(requests) {
                            if (requests[model] === request && model != null) requests[model] = request.copy(
                                snapshot = request.snapshot.copy(status = LyricsStatus.UNAVAILABLE),
                            )
                        }
                        return@safeHook
                    }
                    val song = pointer.javaClass.getMethod("get").invoke(pointer) ?: return@safeHook
                    if (song.call("getAdamId").toString() != request.id ||
                        (song.call("getQueueId") as? Number)?.toLong() != request.queueId) return@safeHook
                    // Copy synchronously while the host holds the shared native pointer alive.
                    // No native pointers escape to a worker or a later render frame.
                    val lines = AppleLyricsSource.copyLines(song)
                    moduleInfo("Apple Music lyrics copied: lines=${lines.size}")
                    synchronized(requests) {
                        if (requests[model] === request && model != null) requests[model] = request.copy(
                            snapshot = request.snapshot.copy(
                                status = if (lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY,
                                lines = lines,
                            ),
                        )
                    }
                }
                result
            }
        }

        @Synchronized private fun hook(executable: Executable, id: String, hooker: XposedInterface.Hooker) {
            if (executable in installed) return
            module.installHook(executable, id, hooker)
            installed.add(executable)
        }

        private fun Any.call(name: String): Any? = javaClass.getMethod(name).invoke(this)
    }
}
