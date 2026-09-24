package com.jaco.musicenhance.adapter.apple

import android.os.SystemClock
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.WeakHashMap

/** Times only module-originated writes; preserves the host's serial queue and database locking. */
internal object AppleFavoriteOperationHooks {
    private val requestedAtMs = WeakHashMap<Any, Long>()

    fun track(item: Any) { synchronized(requestedAtMs) { requestedAtMs[item] = SystemClock.elapsedRealtime() } }
    fun forget(item: Any) { synchronized(requestedAtMs) { requestedAtMs.remove(item) } }

    fun install(loader: ClassLoader) = safeHook("Apple Music favorite operation timing") {
        val operationType = loader.loadClass("B5.L")
        val items = operationType.getField("h")
        module.installHook(operationType.getMethod("x", loader.loadClass("Sf.q")), "musicenhance.apple.favorite.write", Hooker { chain ->
            val startedAtMs = SystemClock.elapsedRealtime()
            val requested = (items.get(chain.thisObject) as? List<*>)?.firstNotNullOfOrNull { item ->
                synchronized(requestedAtMs) { requestedAtMs[item] }
            }
            if (requested != null) moduleInfo("Apple Music favorite write started: queueMs=${startedAtMs - requested}")
            try { chain.proceed() } finally {
                if (requested != null) moduleInfo("Apple Music favorite write returned: nativeMs=${SystemClock.elapsedRealtime() - startedAtMs}")
            }
        })
    }
}
