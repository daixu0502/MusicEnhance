package vip.chener.musicenhance.adapter.apple

import android.os.SystemClock
import vip.chener.musicenhance.hook.moduleInfo
import java.lang.reflect.Proxy
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Same native operation as player.b1.s0 (the star button), including account/library checks. */
internal class AppleFavoriteSource(loader: ClassLoader, private val executor: Executor = worker) {
    private val api by lazy { AppleFavoriteApi(loader) }
    private val libraryType get() = api.libraryType
    private val likeType get() = api.likeType
    private val consumerType get() = api.consumerType
    private val main = Handler(Looper.getMainLooper())
    private var identity: String? = null
    private var libraryItem: Any? = null
    private var subscription: Any? = null
    @Volatile private var generation = 0
    private var loading = false
    private var nextReadAtMs = 0L
    private data class PendingChange(val item: Any, val previous: Boolean, val startedAtMs: Long)
    private var pendingChange: PendingChange? = null
    val isPending: Boolean get() = pendingChange != null

    fun state(item: Any?): Boolean? {
        val key = item?.let(ApplePlaybackSource::identity)
        if (identity != key) {
            release()
            identity = key
        }
        if (item == null || !isAvailable(item)) return null
        pendingChange?.let { pending ->
            // Keep the exact object given to the native action: its success callback mutates it.
            // A periodic database snapshot must not disconnect us from that confirmation.
            if (isConfirmed(pending.item, pending)) {
                cancelQuery()
                confirm(pending.item, pending)
            } else if (SystemClock.elapsedRealtime() - pending.startedAtMs >= CONFIRMATION_TIMEOUT_MS) {
                pendingChange = null
                nextReadAtMs = 0L
                moduleInfo("Apple Music favorite confirmation timed out; rereading library")
            }
        }
        if (!loading && SystemClock.elapsedRealtime() >= nextReadAtMs) read(item)
        return libraryItem?.let(ApplePlaybackSource::favorite)
    }

    private fun read(item: Any) {
        cancelQuery()
        val request = generation
        loading = true
        nextReadAtMs = SystemClock.elapsedRealtime() + if (pendingChange != null) CONFIRM_POLL_MS else STATE_REFRESH_MS
        fun consumer(callback: (Any?) -> Unit): Any = Proxy.newProxyInstance(consumerType.classLoader, arrayOf(consumerType)) { proxy, method, args ->
            when (method.name) {
                "accept" -> {
                    val value = args?.firstOrNull()
                    val deliver = { if (generation == request) callback(value) }
                    deliverOnMain(deliver)
                    null
                }
                "toString" -> "MusicEnhance Apple favorite callback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        try { executor.execute {
            if (generation != request) return@execute
            try {
                // Rx factories can defer database work until subscribe; both operations stay off UI.
                val single = api.query.invoke(null, item)
                val disposable = api.subscribe.invoke(single, consumer { optional ->
                    loading = false
                    val result = optional?.let(api::optionalItem)
                    val pending = pendingChange
                    if (pending == null) libraryItem = result
                    else if (result != null && isConfirmed(result, pending)) confirm(result, pending)
                }, consumer { error ->
                    loading = false
                    moduleInfo("Apple Music favorite query failed: $error")
                })
                // A release or next song may have arrived while the host was reading its library.
                deliverOnMain {
                    if (generation == request) subscription = disposable else dispose(disposable)
                }
            } catch (error: Throwable) {
                deliverOnMain {
                    if (generation == request) {
                        loading = false
                        moduleInfo("Apple Music favorite query failed: ${error.cause ?: error}")
                    }
                }
            }
        } } catch (error: RuntimeException) {
            loading = false
            throw error
        }
    }

    fun isAvailable(item: Any): Boolean {
        val library = api.library.invoke(null) ?: return false
        return libraryType.getMethod("isReady").invoke(library) == true && api.supported.invoke(null, item) == true
    }

    fun toggle(item: Any, previous: Boolean): Boolean {
        if (pendingChange != null) return false
        if (!isAvailable(item) || ApplePlaybackSource.identity(item) != identity) return false
        val current = libraryItem ?: return false
        if (ApplePlaybackSource.favorite(current) != previous) return false
        cancelQuery()
        val target = likeType.getField(if (previous) "None" else "Liked").get(null)
        try {
            api.update.invoke(null, current, target, if (previous) 1 else 2, true)
        } catch (error: Throwable) {
            throw error
        }
        pendingChange = PendingChange(current, previous, SystemClock.elapsedRealtime())
        nextReadAtMs = SystemClock.elapsedRealtime() + CONFIRM_POLL_MS
        // Native z8.i updates the item's state only after its library action succeeds.
        return true
    }

    private fun isConfirmed(item: Any, pending: PendingChange): Boolean =
        ApplePlaybackSource.favorite(item) == !pending.previous

    private fun confirm(item: Any, pending: PendingChange) {
        libraryItem = item
        pendingChange = null
        nextReadAtMs = SystemClock.elapsedRealtime() + STATE_REFRESH_MS
        moduleInfo("Apple Music favorite confirmed: latencyMs=${SystemClock.elapsedRealtime() - pending.startedAtMs}")
    }

    private fun cancelQuery() {
        generation++
        dispose(subscription)
        subscription = null
        loading = false
    }

    fun release() {
        // Queued ownership deliveries must still run so stale native subscriptions are disposed.
        cancelQuery()
        identity = null
        libraryItem = null
        pendingChange = null
        nextReadAtMs = 0L
    }

    private fun deliverOnMain(deliver: () -> Unit) {
        val guarded = {
            runCatching(deliver).onFailure { error ->
                loading = false
                moduleInfo("Apple Music favorite result delivery failed: ${error.cause ?: error}")
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded() else main.post(guarded)
    }

    private fun dispose(disposable: Any?) {
        disposable?.let { runCatching { api.dispose.invoke(it) }.onFailure { error ->
            moduleInfo("Apple Music favorite subscription release failed: ${error.cause ?: error}")
        } }
    }

    companion object {
        val worker: Executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
            { task -> Thread(task, "MusicEnhance-AppleFavorite").apply { isDaemon = true } })
        private const val STATE_REFRESH_MS = 1_500L
        private const val CONFIRM_POLL_MS = 200L
        private const val CONFIRMATION_TIMEOUT_MS = 10_000L
    }
}
