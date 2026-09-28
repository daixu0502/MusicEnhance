package com.jaco.musicenhance.adapter.lxx

import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.RepeatMode
import org.json.JSONObject
import java.lang.ref.WeakReference

/** LX-X 26.09.20 owns playlist modes in JS, not TrackPlayer's temporary native queue. */
internal object LxxControlSource {
    const val MARKER = "MusicEnhance.Lxx.Controls.v1"
    private var api: Api? = null
    @Volatile private var connection: Connection? = null

    private class Connection(module: Any, runtime: Any, val mode: RepeatMode) {
        val module = WeakReference(module)
        val runtime = WeakReference(runtime)
    }

    fun install(loader: ClassLoader) = safeHook("LX-X controls bridge") { api = Api(loader) }

    fun receive(module: Any, payload: String) {
        val currentApi = api ?: return
        val mode = decode(payload)
        val context = currentApi.context.invoke(module)
        val runtime = currentApi.runtime.invoke(context) ?: return
        connection = Connection(module, runtime, mode)
    }

    fun onModuleDestroyed(module: Any?) {
        if (connection?.module?.get() === module) connection = null
    }

    fun snapshot(title: String) = PlayerControlState(
        repeatMode = connection?.takeIf { it.runtime.get() != null }?.mode ?: RepeatMode.UNKNOWN,
        // User-requested display policy, independent of the host's actual favorites.
        favorite = true, songTitle = title,
    )

    fun cycleRepeat(): Boolean {
        val currentApi = api ?: return false
        val current = connection ?: return false
        if (current.mode == RepeatMode.UNKNOWN) return false
        val runtime = current.runtime.get() ?: return false
        return runCatching {
            if (currentApi.destroyed.invoke(runtime) == true) return false
            // Native arrays require SoLoader. Only create one after JS reports controls ready.
            val arguments = currentApi.createArray.invoke(null)
            currentApi.call.invoke(runtime, "MusicEnhanceLxxControls", "cycleRepeat", arguments)
            true // Accepted by the JS queue; displayed mode changes only on host confirmation.
        }.onFailure { moduleInfo("LX-X repeat command failed: ${it.cause ?: it}") }.getOrDefault(false)
    }

    internal fun decode(payload: String): RepeatMode {
        require(payload.length <= 1024) { "LX-X controls snapshot exceeds limit" }
        val json = JSONObject(payload)
        if (!json.optBoolean("ready")) return RepeatMode.UNKNOWN
        return when (json.optString("mode")) {
            "listLoop" -> RepeatMode.LIST_LOOP
            "random" -> RepeatMode.SHUFFLE
            "list" -> RepeatMode.SEQUENTIAL
            "singleLoop" -> RepeatMode.SINGLE_LOOP
            "none" -> RepeatMode.SINGLE_PLAY
            else -> RepeatMode.UNKNOWN
        }
    }

    private class Api(loader: ClassLoader) {
        val context = loader.loadClass("com.facebook.react.bridge.ReactContextBaseJavaModule")
            .getDeclaredMethod("getReactApplicationContext").apply { isAccessible = true }
        val runtime = loader.loadClass("com.facebook.react.bridge.ReactContext").getMethod("getCatalystInstance")
        private val catalyst = loader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val destroyed = catalyst.getMethod("isDestroyed")
        val call = catalyst.getMethod("callFunction", String::class.java, String::class.java,
            loader.loadClass("com.facebook.react.bridge.NativeArray"))
        val createArray = loader.loadClass("com.facebook.react.bridge.Arguments").getMethod("createArray")
    }
}
