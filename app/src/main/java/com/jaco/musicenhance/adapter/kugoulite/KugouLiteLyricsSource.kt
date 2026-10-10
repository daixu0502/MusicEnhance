package com.jaco.musicenhance.adapter.kugoulite

import android.os.SystemClock
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.adapter.kugoucommon.KugouLyricsRules
import java.lang.reflect.Method

internal object KugouLiteLyricsSource {
    private val sources = KugouLiteLyricsStore()
    private val installed = mutableSetOf<Method>()

    fun install(loader: ClassLoader) {
        val interfaces = KugouLyricsRules(loader, "kugoulite")
        safeHook("Kugou Lite lyric download identity") {
            val type = interfaces.downloader
            val getHash = KugouLiteApi(loader).verified("lyrics.download-hash", type, "e", String::class.java)
            val getPath = interfaces.downloadedPath
            val download = interfaces.download
            if (download in installed) return@safeHook
            module.installHook(download, "musicenhance.kugoulite.lyric.download") { chain ->
                val hash = runCatching { getHash.invoke(chain.thisObject) as? String }.getOrNull()
                val result = chain.proceed()
                safeHook("Kugou Lite lyric download result") {
                    // Use the original request identity, never the song playing on completion.
                    sources.associate(hash, getPath.invoke(chain.thisObject) as? String)
                }
                result
            }
            installed += download
        }
        safeHook("Kugou Lite lyric source") {
            val load = interfaces.parseFile
            if (load in installed) return@safeHook
            module.installHook(load, "musicenhance.kugoulite.lyric.path") { chain ->
                val result = chain.proceed()
                safeHook("Kugou Lite lyric identity") {
                    sources.parsed(chain.args[0] as? String, result?.let { interfaces.resultData(it.javaClass).get(it) })
                }
                result
            }
            installed += load
        }
    }

    /** All native loading is called by CachedNativeLyricsProvider's background worker. */
    class Reader(loader: ClassLoader) {
        private val api = KugouLiteLyricsApi(loader)
        private var lastData: Any? = null
        private var lastSnapshot: LyricsSnapshot? = null
        private var requestedHash = ""
        private var nextLoadAtMs = 0L
        private var lastReadState = ""

        fun readLyrics(player: PlayerSnapshot): LyricsSnapshot? {
            val song = api.currentSong() ?: return unavailable("waiting-native-song")
            val hash = song.hash
            if (hash.isBlank()) return unavailable("missing-audio-hash")
            if (song.title.trim() != player.title.trim()) {
                return unavailable("waiting-metadata-match(native=${song.title}, media=${player.title})")
            }
            val key = "$hash\u0000${player.metadataKey}"
            val stored = sources.find(hash)
            // A cached TXT/empty parse must not prevent the timed-lyric download fallback.
            var data = stored?.takeIf { api.lines(it).isNotEmpty() }
                ?: api.currentData(hash)?.takeIf { api.lines(it).isNotEmpty() }
            if (data == null && (requestedHash != hash || SystemClock.elapsedRealtime() >= nextLoadAtMs)) {
                requestedHash = hash
                // The host's lyric tab need not have been opened. Reuse its downloader/cache.
                data = try { api.load(song) } finally {
                    nextLoadAtMs = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
                }
                if (data != null) sources.put(hash, data)
                moduleInfo("Kugou Lite native lyric load: data=${data != null}")
            }
            data ?: return unavailable("no-native-lyrics")
            val currentSong = api.currentSong() ?: return null
            if (!currentSong.hash.equals(hash, ignoreCase = true)) return unavailable("discarded-old-song")
            if (lastData === data && lastSnapshot?.trackKey == key) return lastSnapshot
            val lines = api.lines(data)
            reportState(if (lines.isEmpty()) "no-timed-lines" else "ready")
            return LyricsSnapshot(key, if (lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, lines).also {
                if (lines.isNotEmpty()) {
                    lastData = data
                    lastSnapshot = it
                }
            }
        }

        private fun unavailable(reason: String): LyricsSnapshot? {
            reportState(reason)
            return null
        }

        private fun reportState(state: String) {
            if (lastReadState != state) {
                lastReadState = state
                moduleInfo("Kugou Lite lyric state=$state")
            }
        }
    }

    private const val RETRY_INTERVAL_MS = 30_000L
}
