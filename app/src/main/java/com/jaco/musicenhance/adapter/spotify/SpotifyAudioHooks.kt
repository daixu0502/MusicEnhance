package com.jaco.musicenhance.adapter.spotify

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/** Observes Spotify's native output session without changing its audio backend or permissions. */
internal object SpotifyAudioHooks {
    data class State(val sessionId: Int = 0, val playing: Boolean = false, val created: Boolean = false)

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val installed = mutableSetOf<Method>()
    private val listeners = linkedSetOf<(State) -> Unit>()
    private val eventSequence = AtomicLong()
    private var renderer = WeakReference<Any>(null)
    private var lastObservedEvent = 0L
    @Volatile private var state = State()

    fun currentState(): State = state

    fun addListener(listener: (State) -> Unit) {
        synchronized(this) { listeners.add(listener) }
        dispatch {
            if (synchronized(this) { listener in listeners }) deliver(listener, state)
        }
    }

    fun removeListener(listener: (State) -> Unit) {
        synchronized(this) { listeners.remove(listener) }
    }

    fun install(loader: ClassLoader) {
        HostInterfaces.onResolved(loader, "spotify.audio.hooks") { install(loader) }
        safeHook("Spotify native audio session lifecycle") {
            val methods = SpotifyInterfaceRules.audioRendererMethods(loader)
            hook(methods.created, "created") { owner, _, event ->
                // Read only at host lifecycle boundaries. Rendering never touches a native object.
                val sessionId = methods.sessionId.invoke(owner) as Int
                val playing = methods.playState.invoke(owner) == AudioTrack.PLAYSTATE_PLAYING
                update(owner, event, replace = true) { State(sessionId.coerceAtLeast(0), playing, created = true) }
            }
            hook(methods.destroyed, "destroyed") { owner, _, event ->
                update(owner, event) { State() }
            }
            hook(methods.sessionChanged, "session-changed") { owner, args, event ->
                val sessionId = args.firstOrNull() as? Int ?: return@hook
                update(owner, event) { it.copy(sessionId = sessionId.coerceAtLeast(0)) }
            }
            hook(methods.paused, "paused") { owner, args, event ->
                val paused = args.firstOrNull() as? Boolean ?: return@hook
                update(owner, event) { it.copy(playing = !paused && it.created) }
            }
            hook(methods.flushStarted, "flush-started") { owner, _, event ->
                update(owner, event) { it.copy(playing = false) }
            }
            hook(methods.flushCompleted, "flush-completed") { owner, _, event ->
                update(owner, event) { it.copy(playing = false) }
            }
        }
    }

    private fun hook(method: Method, event: String, observe: (Any, List<Any?>, Long) -> Unit) {
        synchronized(installed) {
            if (method in installed) return
            module.installHook(method, "musicenhance.spotify.audio.$event") { chain ->
                val sequence = eventSequence.incrementAndGet()
                // Host exceptions and return values retain their original behavior.
                chain.proceed().also {
                    chain.thisObject?.let { owner ->
                        safeHook("Spotify native audio $event") { observe(owner, chain.args, sequence) }
                    }
                }
            }
            installed.add(method)
        }
    }

    private fun update(owner: Any, event: Long, replace: Boolean = false, transform: (State) -> State) {
        val changed = synchronized(this) {
            // Native listener dispatch can reenter a newer lifecycle before this hook returns.
            if (event < lastObservedEvent) return
            if (!replace && renderer.get() !== owner) return
            lastObservedEvent = event
            if (replace) renderer = WeakReference(owner)
            val next = transform(state)
            if (next == state) false else { state = next; true }
        }
        if (!changed) return
        dispatch {
            // Read the latest state when queued: an old callback cannot reattach a released session.
            val snapshot = state
            val currentListeners = synchronized(this) { listeners.toList() }
            currentListeners.forEach { listener ->
                if (synchronized(this) { listener in listeners }) deliver(listener, snapshot)
            }
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun deliver(listener: (State) -> Unit, snapshot: State) {
        safeHook("Spotify native audio listener") { listener(snapshot) }
    }
}
