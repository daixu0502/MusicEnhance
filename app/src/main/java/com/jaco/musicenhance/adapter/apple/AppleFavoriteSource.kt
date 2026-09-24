package com.jaco.musicenhance.adapter.apple

import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import java.lang.reflect.Proxy

/** Same native operation as player.b1.s0 (the star button), including account/library checks. */
internal class AppleFavoriteSource(loader: ClassLoader) {
    private val libraryType = loader.loadClass("com.apple.android.medialibrary.library.a")
    private val itemType = loader.loadClass("com.apple.android.music.model.BaseContentItem")
    private val likeType = loader.loadClass("com.apple.android.medialibrary.library.MediaLibrary\$f")
    private val actions = loader.loadClass("B8.s")
    private val supported = actions.getMethod("r", itemType)
    private val update = actions.getMethod("u", itemType, likeType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val query = actions.getMethod("w", itemType)
    private val singleType = loader.loadClass("Sf.o")
    private val consumerType = loader.loadClass("Wf.e")
    private val disposableType = loader.loadClass("Tf.b")
    private val mainScheduler = loader.loadClass("Rf.a").getMethod("a").invoke(null)
    private val observeOn = singleType.getMethod("i", loader.loadClass("Sf.n"))
    private val subscribe = singleType.getMethod("k", consumerType, consumerType)
    private var identity: String? = null
    private var libraryItem: Any? = null
    private var subscription: Any? = null
    private var generation = 0
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
                AppleFavoriteOperationHooks.forget(pending.item)
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
                "accept" -> { if (generation == request) callback(args?.firstOrNull()); null }
                "toString" -> "MusicEnhance Apple favorite callback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        try {
            // The native library schedules its own database work; only the copied result reaches UI.
            val single = observeOn.invoke(query.invoke(null, item), mainScheduler)
            subscription = subscribe.invoke(single, consumer { optional ->
                loading = false
                val result = optional?.takeIf { it.javaClass.getMethod("b").invoke(it) != true }
                    ?.let { it.javaClass.getMethod("a").invoke(it) }
                val pending = pendingChange
                if (pending == null) libraryItem = result
                else if (result != null && isConfirmed(result, pending)) confirm(result, pending)
            }, consumer { error ->
                loading = false
                moduleInfo("Apple Music favorite query failed: $error")
            })
        } catch (error: Throwable) {
            loading = false
            throw error
        }
    }

    fun isAvailable(item: Any): Boolean {
        val library = libraryType.getMethod("W").invoke(null) ?: return false
        return libraryType.getMethod("isReady").invoke(library) == true && supported.invoke(null, item) == true
    }

    fun toggle(item: Any, previous: Boolean): Boolean {
        if (pendingChange != null) return false
        if (!isAvailable(item) || ApplePlaybackSource.identity(item) != identity) return false
        val current = libraryItem ?: return false
        if (ApplePlaybackSource.favorite(current) != previous) return false
        cancelQuery()
        val target = likeType.getField(if (previous) "None" else "Liked").get(null)
        AppleFavoriteOperationHooks.track(current)
        try {
            update.invoke(null, current, target, if (previous) 1 else 2, true)
        } catch (error: Throwable) {
            AppleFavoriteOperationHooks.forget(current)
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
        AppleFavoriteOperationHooks.forget(pending.item)
        libraryItem = item
        pendingChange = null
        nextReadAtMs = SystemClock.elapsedRealtime() + STATE_REFRESH_MS
        moduleInfo("Apple Music favorite confirmed: latencyMs=${SystemClock.elapsedRealtime() - pending.startedAtMs}")
    }

    private fun cancelQuery() {
        generation++
        subscription?.let { runCatching { disposableType.getMethod("f").invoke(it) } }
        subscription = null
        loading = false
    }

    fun release() {
        pendingChange?.let { AppleFavoriteOperationHooks.forget(it.item) }
        cancelQuery()
        identity = null
        libraryItem = null
        pendingChange = null
        nextReadAtMs = 0L
    }

    private companion object {
        const val STATE_REFRESH_MS = 1_500L
        const val CONFIRM_POLL_MS = 200L
        const val CONFIRMATION_TIMEOUT_MS = 10_000L
    }
}
