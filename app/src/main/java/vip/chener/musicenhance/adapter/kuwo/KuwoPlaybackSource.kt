package vip.chener.musicenhance.adapter.kuwo

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import vip.chener.musicenhance.hook.module
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.hook.safeHook
import vip.chener.musicenhance.player.model.LyricLine
import vip.chener.musicenhance.player.model.LyricsSnapshot
import vip.chener.musicenhance.player.model.LyricsStatus
import vip.chener.musicenhance.player.model.PlayerSnapshot
import java.util.WeakHashMap
import java.lang.reflect.Method

/** Kuwo 12.2.2.4's native lyric action and playback data, independent of MediaSession callbacks. */
internal class KuwoPlaybackSource(
    loader: ClassLoader,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val api by lazy { Api(loader) }
    private var pendingSeek: Runnable? = null
    private var generation = 0L
    private var released = false

    @Synchronized
    fun seekAndPlay(positionMs: Long, player: PlayerSnapshot) {
        if (released || player.durationMs <= 0) return
        pendingSeek?.let(handler::removeCallbacks)
        val request = ++generation
        val targetMs = positionMs.coerceIn(0, minOf(player.durationMs, Int.MAX_VALUE.toLong())).toInt()
        val task = Runnable {
            synchronized(this) {
                if (!isCurrent(request)) return@Runnable
                pendingSeek = null
                runCatching {
                    val native = api
                    val control = native.control() ?: return@runCatching
                    val song = native.song(control)?.takeIf { it.matches(player) } ?: return@runCatching
                    fun canApply() = isCurrent(request) && native.control() === control && native.song(control) == song
                    if (!canApply()) return@runCatching
                    // DrawLyricView.f seeks first, then explicitly continues unless already playing.
                    // The facade also coordinates native video UI, so keep the host's main-thread order.
                    native.seek(control, targetMs)
                    if (!canApply()) return@runCatching
                    val status = native.status(control) ?: return@runCatching
                    if (!canApply()) return@runCatching
                    val resumed = if (status == "PLAYING") null else native.resume(control)
                    moduleInfo("Kuwo lyric seek: songId=${song.id}, targetMs=$targetMs, status=$status, resumeResult=$resumed")
                }.onFailure { moduleInfo("Kuwo lyric seek failed: ${it.cause ?: it}") }
            }
        }
        pendingSeek = task
        if (!handler.post(task)) pendingSeek = null
    }

    @Synchronized
    fun release() {
        released = true
        generation++
        pendingSeek?.let(handler::removeCallbacks)
        pendingSeek = null
    }

    private fun isCurrent(request: Long) = !released && generation == request

    private data class Song(val id: Long, val title: String, val artist: String) {
        fun matches(player: PlayerSnapshot) = title.isNotBlank() && title == player.title.trim() &&
            (artist.isBlank() || player.artist.isBlank() || artist == player.artist.trim())
    }

    private class Api(loader: ClassLoader) {
        private val getControl = KuwoInterfaceRules.playerFactory(loader)
        private val controlType = getControl.returnType
        private val getSong = KuwoInterfaceRules.currentSong(loader, controlType)
        private val getStatus = controlType.getMethod("getStatus")
        private val seek = controlType.getMethod("seek", Int::class.javaPrimitiveType)
        private val resume = controlType.getMethod("continuePlay")
        private val songType = loader.loadClass("cn.kuwo.base.bean.Music")
        private val id = songType.getField("rid")
        private val title = songType.getField("name")
        private val artist = songType.getField("artist")

        fun control(): Any? = getControl.invoke(null)
        fun song(control: Any): Song? {
            val song = getSong.invoke(control) ?: return null
            return Song(id.getLong(song), (title.get(song) as? String).orEmpty().trim(),
                (artist.get(song) as? String).orEmpty().trim())
        }
        fun status(control: Any): String? = (getStatus.invoke(control) as? Enum<*>)?.name
        fun seek(control: Any, positionMs: Int) { seek.invoke(control, positionMs) }
        fun resume(control: Any): Boolean = resume.invoke(control) == true
    }

    companion object {
        private val lyricSongs = WeakHashMap<Any, Long>()
        private val installedDispatchers = mutableSetOf<Method>()

        /** The dispatcher supplies the song identity before publishing the decoded lyric object. */
        fun install(loader: ClassLoader) = safeHook("Kuwo lyrics association") {
            val method = KuwoInterfaceRules.lyricsDispatch(loader)
            if (method in installedDispatchers) return@safeHook
            module.installHook(method, "musicenhance.kuwo.lyrics") { chain ->
                safeHook("Kuwo lyric identity") {
                    val song = chain.args[0]
                    val data = chain.args[2]
                    if (song != null && data != null) {
                        val id = song.javaClass.getField("rid").getLong(song)
                        synchronized(lyricSongs) { lyricSongs[data] = id }
                    }
                }
                chain.proceed()
            }
            installedDispatchers.add(method)
        }
    }

    class Reader(private val loader: ClassLoader) {
        // An unresolved optional lyric contract must not prevent the independent player opening.
        // Kotlin lazy retries after an exception, once the background discovery batch completes.
        private val api by lazy { LyricsApi(loader) }
        private val artwork by lazy {
            val factory = KuwoInterfaceRules.lyricsFactory(loader)
            factory to KuwoInterfaceRules.originalArtwork(loader, factory.returnType)
        }
        private var lastData: Any? = null
        private var lastOffsetMs = Long.MIN_VALUE
        private var lastSnapshot: LyricsSnapshot? = null

        fun readLyrics(player: PlayerSnapshot): LyricsSnapshot? {
            val native = api
            val control = native.getControl.invoke(null) ?: return null
            val song = native.getSong.invoke(control) ?: return null
            val id = song.javaClass.getField("rid").getLong(song)
            if (song.javaClass.getField("name").get(song) != player.title) return null
            val manager = native.getLyrics.invoke(null) ?: return null
            val data = native.originalLyrics.invoke(manager) ?: return null
            if (synchronized(lyricSongs) { lyricSongs[data] } != id) return null
            val offsetMs = (native.offset.invoke(data) as Number).toLong()
            val key = "$id\u0000${player.metadataKey}"
            if (lastData === data && lastOffsetMs == offsetMs && lastSnapshot?.trackKey == key) return lastSnapshot
            val rows = native.rows.invoke(data) as? List<*> ?: return null
            val lines = rows.mapNotNull { row ->
                if (row == null) return@mapNotNull null
                val timeMs = (native.start.get(row) as? Number)?.toLong() ?: return@mapNotNull null
                val text = (native.text.get(row) as? String)?.trim().orEmpty()
                if (text.isBlank()) null else LyricLine((timeMs - offsetMs).coerceAtLeast(0), text)
            }.sortedBy { it.startMs }
            lastData = data
            lastOffsetMs = offsetMs
            return LyricsSnapshot(key, if (lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, lines)
                .also { lastSnapshot = it }
        }

        /** The module manager supplies artwork without creating a flip page or scanning Views. */
        fun readArtwork(): Bitmap? = runCatching {
            val (factory, getter) = artwork
            val manager = factory.invoke(null) ?: return null
            (getter.invoke(manager) as? Bitmap)?.takeUnless { it.isRecycled }
        }.getOrNull()

        private class LyricsApi(loader: ClassLoader) {
            val getControl = KuwoInterfaceRules.playerFactory(loader)
            val getSong = KuwoInterfaceRules.currentSong(loader, getControl.returnType)
            val getLyrics = KuwoInterfaceRules.lyricsFactory(loader)
            private val data = KuwoInterfaceRules.lyricsDispatch(loader).parameterTypes[2]
            val originalLyrics = KuwoInterfaceRules.originalLyrics(loader, getLyrics.returnType, data)
            val offset = KuwoInterfaceRules.lyricOffset(loader, data)
            val rows = KuwoInterfaceRules.lyricRows(loader, data)
            private val row = KuwoInterfaceRules.lyricRowType(loader, rows)
            val start = KuwoInterfaceRules.lyricStart(loader, row)
            val text = KuwoInterfaceRules.lyricText(loader, row)
        }
    }
}
