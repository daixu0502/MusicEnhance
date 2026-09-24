package com.jaco.musicenhance.adapter.kuwo

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.ImageView
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.WeakHashMap

/** Kuwo's dispatcher supplies the song identity before publishing the decoded lyric object. */
internal object KuwoPlaybackSource {
    private val lyricSongs = WeakHashMap<Any, Long>()

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
