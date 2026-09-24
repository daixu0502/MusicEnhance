package com.jaco.musicenhance.adapter.apple

import android.text.Html
import com.jaco.musicenhance.player.model.LyricLine

/** Copy native pointers while the host owns them. Only plain text and millisecond times escape. */
internal object AppleLyricsSource {
    fun copyLines(song: Any): List<LyricLine> {
        // Native timing enum: None=0, Line=1, Word=2. Plain lyrics have no seekable timeline.
        if ((song.call("getTiming") as? Number)?.toInt() !in 1..2) return emptyList()
        val sections = song.call("getSections") ?: return emptyList()
        val output = ArrayList<LyricLine>()
        for (sectionPointer in sections.vectorItems()) {
            val section = sectionPointer.call("get") ?: continue
            val lines = section.call("getLines") ?: continue
            for (linePointer in lines.vectorItems()) {
                val line = linePointer.call("get") ?: continue
                val startMs = (line.call("getBegin") as? Number)?.toLong() ?: continue
                val text = Html.fromHtml(line.call("getHtmlLineText") as? String ?: "", Html.FROM_HTML_MODE_LEGACY).toString().trim()
                if (startMs >= 0 && text.isNotBlank()) output += LyricLine(startMs, text)
                if (output.size >= MAX_LINES) return output.sortedBy { it.startMs }
            }
        }
        return output.sortedBy { it.startMs }
    }

    private fun Any.call(name: String): Any? = javaClass.getMethod(name).invoke(this)
    private fun Any.vectorItems(): Sequence<Any> {
        val size = (call("size") as Number).toLong().coerceIn(0, MAX_LINES.toLong())
        val get = javaClass.getMethod("get", Long::class.javaPrimitiveType)
        return (0L until size).asSequence().mapNotNull { get.invoke(this, it) }
    }

    private const val MAX_LINES = 2_000
}
