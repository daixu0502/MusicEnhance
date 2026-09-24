package com.jaco.musicenhance.adapter.kuwo

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.WeakHashMap

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
        private val getControl = loader.loadClass("q1.b").getMethod("e0")
        private val controlType = loader.loadClass("cn.kuwo.mod.playcontrol.c")
        private val getSong = controlType.getMethod("X")
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

        /** The dispatcher supplies the song identity before publishing the decoded lyric object. */
        fun install(loader: ClassLoader) = safeHook("Kuwo lyrics association") {
            val dispatcher = loader.loadClass("cn.kuwo.mod.lyrics.d0")
            val method = dispatcher.declaredMethods.single { it.name == "i" && it.parameterCount == 5 }
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
        }
    }

    class Reader(loader: ClassLoader) {
        private val modules = loader.loadClass("q1.b")
        private val lyricsApi = loader.loadClass("cn.kuwo.mod.lyrics.f")
        private val playApi = loader.loadClass("cn.kuwo.mod.playcontrol.c")
        private val dataApi = loader.loadClass("cn.kuwo.mod.lyrics.e")
        private val lineType = loader.loadClass("cn.kuwo.mod.lyrics.j")
        private var lastData: Any? = null
        private var lastOffsetMs = Long.MIN_VALUE
        private var lastSnapshot: LyricsSnapshot? = null

        fun readLyrics(player: PlayerSnapshot): LyricsSnapshot? {
            val control = modules.getMethod("e0").invoke(null) ?: return null
            val song = playApi.getMethod("X").invoke(control) ?: return null
            val id = song.javaClass.getField("rid").getLong(song)
            if (song.javaClass.getField("name").get(song) != player.title) return null
            val manager = modules.getMethod("O").invoke(null) ?: return null
            val data = lyricsApi.getMethod("Wc").invoke(manager) ?: return null
            if (synchronized(lyricSongs) { lyricSongs[data] } != id) return null
            val offsetMs = (dataApi.getMethod("a").invoke(data) as Number).toLong()
            val key = "$id\u0000${player.metadataKey}"
            if (lastData === data && lastOffsetMs == offsetMs && lastSnapshot?.trackKey == key) return lastSnapshot
            val rows = dataApi.getMethod("q").invoke(data) as? List<*> ?: return null
            val startField = lineType.getField("b")
            val textField = lineType.getField("d")
            val lines = rows.mapNotNull { row ->
                if (row == null) return@mapNotNull null
                val timeMs = (startField.get(row) as? Number)?.toLong() ?: return@mapNotNull null
                val text = (textField.get(row) as? String)?.trim().orEmpty()
                if (text.isBlank()) null else LyricLine((timeMs - offsetMs).coerceAtLeast(0), text)
            }.sortedBy { it.startMs }
            lastData = data
            lastOffsetMs = offsetMs
            return LyricsSnapshot(key, if (lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, lines)
                .also { lastSnapshot = it }
        }

        /** The native flip page has separate iv_cover and iv_blur_bg images of the same size. */
        fun readArtwork(root: ViewGroup): Bitmap? = runCatching {
            val image = NativePlayerViews.find(root) { NativePlayerViews.resourceName(it) == "iv_cover" } as? ImageView
            ((image?.drawable as? BitmapDrawable)?.bitmap)?.takeUnless { it.isRecycled }?.let { return it }
            val manager = modules.getMethod("O").invoke(null) ?: return null
            (lyricsApi.getMethod("h").invoke(manager) as? Bitmap)?.takeUnless { it.isRecycled }
        }.getOrNull()
    }
}
