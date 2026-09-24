package com.jaco.musicenhance.hook

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/** Keeps only FlipHome's registered callbacks and their most recent real foreground event. */
internal class CoverActivityObservers {
    private class Observer(
        val token: IBinder,
        val userId: Int,
        val notifyResumed: (Intent) -> Unit,
        val onDeath: IBinder.DeathRecipient,
    ) {
        var registered = true
        var resumedIntent: Intent? = null
    }

    private val observers = ConcurrentHashMap<IBinder, Observer>()

    fun register(token: IBinder, userId: Int, notifyResumed: (Intent) -> Unit) {
        val observer = Observer(token, userId, notifyResumed, IBinder.DeathRecipient { unregister(token) })
        if (observers.putIfAbsent(token, observer) != null) return
        synchronized(observer) {
            if (!observer.registered) return
            try {
                token.linkToDeath(observer.onDeath, 0)
                if (!observer.registered) token.unlinkToDeath(observer.onDeath, 0)
            } catch (error: Exception) {
                unregister(token)
                throw error
            }
        }
    }

    fun unregister(token: IBinder) {
        val observer = observers.remove(token) ?: return
        synchronized(observer) {
            observer.registered = false
            observer.resumedIntent = null
        }
        runCatching { token.unlinkToDeath(observer.onDeath, 0) }
    }

    fun <T> deliverNative(token: IBinder, event: String, intent: Intent?, deliver: () -> T): T {
        val observer = observers[token] ?: return deliver()
        return synchronized(observer) {
            // Serialize native deliveries with a replay so a newer foreground event cannot be
            // overtaken by an old replay. This lock is independent of the WMS global lock.
            val nextIntent = when {
                event == "activityResumed" -> intent?.takeIf { it.component != null }?.let(::Intent)
                intent?.component == observer.resumedIntent?.component -> null
                else -> observer.resumedIntent
            }
            observer.resumedIntent = null
            val result = deliver()
            if (observer.registered) observer.resumedIntent = nextIntent
            result
        }
    }

    fun refresh(component: ComponentName, userId: Int): Int {
        var notified = 0
        observers.values.toList().forEach { observer ->
            synchronized(observer) {
                val intent = observer.resumedIntent
                if (observer.registered && observer.userId == userId && intent?.component == component) {
                    try {
                        observer.notifyResumed(Intent(intent))
                        notified++
                    } catch (error: Exception) {
                        // A dead launcher must not keep an Activity/component alive in system_server.
                        if (!observer.token.isBinderAlive) unregister(observer.token)
                        moduleInfo("Cover activity observer refresh failed: ${error.javaClass.simpleName}")
                    }
                }
            }
        }
        return notified
    }
}
