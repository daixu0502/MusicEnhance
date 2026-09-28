package com.jaco.musicenhance.adapter.lxx

import android.app.Application
import android.content.res.AssetManager
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.model.LyricLine
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** RN's song event includes the real music ID even before TrackPlayer finishes replacing its queue. */
internal object LxxStateBridge {
    internal data class Song(
        val id: String = "", val title: String = "", val artist: String = "", val album: String = "",
        val picture: String = "", val lyric: String = "", val lines: List<LyricLine> = emptyList(),
    ) {
        val metadataKey get() = "$title\u0000$artist\u0000$album"
    }

    @Volatile var state = Song()
        private set
    @Volatile private var scriptFile: File? = null
    private val runtimes = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private var currentModule = WeakReference<Any>(null)
    private const val MARKER = "MusicEnhance.Lxx.State.v1"

    fun prepare(application: Application) {
        // A tiny adapter script, not an artwork cache. It must exist before RN loads the main bundle.
        safeHook("LX-X state bridge script") {
            val script = requireNotNull(javaClass.classLoader?.getResourceAsStream("lxx/state-bridge.js"))
                .bufferedReader().use { it.readText() }
            scriptFile = File(application.codeCacheDir, "musicenhance-lxx-state.js").apply { writeText(script) }
        }
    }

    fun install(loader: ClassLoader) = safeHook("LX-X song state bridge") {
        val catalyst = loader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val loadFile = catalyst.getMethod("loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val lyricType = loader.loadClass("com.lxwalnut.music.mobile.lyric.LyricModule")
        val promiseType = loader.loadClass("com.facebook.react.bridge.Promise")
        val resolve = promiseType.getMethod("resolve", Any::class.java)
        val setLyric = lyricType.getMethod("setLyric", String::class.java, String::class.java, String::class.java, String::class.java, promiseType)
        module.installHook(setLyric, "musicenhance.lxx.state.receive") { chain ->
            val marker = chain.args[0]
            if (marker != MARKER && marker != LxxControlSource.MARKER) chain.proceed() else {
                safeHook("LX-X host song snapshot") {
                    synchronized(this) {
                        currentModule = WeakReference(chain.thisObject)
                        if (marker == MARKER) state = decode(chain.args[1] as String, state)
                        else chain.thisObject?.let { LxxControlSource.receive(it, chain.args[1] as String) }
                    }
                }
                resolve.invoke(chain.args.last(), null)
                null
            }
        }
        module.installHook(catalyst.getMethod("loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType),
            "musicenhance.lxx.state.bootstrap") { chain ->
            val runtime = chain.thisObject
            val file = scriptFile
            if (runtime != null && file != null && (chain.args[1] as? String)?.endsWith("index.android.bundle") == true) {
                safeHook("LX-X install song observer") {
                    if (synchronized(runtimes) { runtimes.add(runtime) }) {
                        // Hermes queues this script before the app bundle; no require() of unknown modules.
                        loadFile.invoke(runtime, file.absolutePath, file.absolutePath, chain.args[2])
                    }
                }
            }
            chain.proceed()
        }
        // Release state when this React module dies; stale module destruction cannot clear a newer one.
        val base = loader.loadClass("com.facebook.react.bridge.BaseJavaModule")
        module.installHook(base.getMethod("onCatalystInstanceDestroy"), "musicenhance.lxx.state.destroy") { chain ->
            synchronized(this) {
                LxxControlSource.onModuleDestroyed(chain.thisObject)
                if (currentModule.get() === chain.thisObject) { currentModule.clear(); state = Song() }
            }
            chain.proceed()
        }
        moduleInfo("LX-X host-only song/lyric bridge installed (26.09.20)")
    }

    internal fun decode(payload: String, previous: Song = Song()): Song {
        require(payload.length <= 600_000) { "LX-X song snapshot exceeds limit" }
        val json = JSONObject(payload)
        fun value(key: String) = if (json.isNull(key)) "" else json.optString(key)
        val id = value("id")
        if (id.isBlank()) return Song()
        val lyric = value("lrc")
        val lines = if (id == previous.id && lyric == previous.lyric) previous.lines else LxxLyricsProvider.parse(lyric)
        return Song(id, value("name"), value("singer"), value("album"), value("pic"), lyric, lines)
    }
}
