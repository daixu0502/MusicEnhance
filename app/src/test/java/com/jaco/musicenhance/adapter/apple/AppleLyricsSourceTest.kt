package com.jaco.musicenhance.adapter.apple

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppleLyricsSourceTest {
    @Test fun nativeLyricsKeepMillisecondTimingDecodeMarkupAndSortLines() {
        val song = Song(1, listOf(Line(2500, "<span>第二句</span>"), Line(1000, "First &amp; second"), Line(-1, "invalid"), Line(0, " ")))
        val result = AppleLyricsSource.copyLines(song)
        assertEquals(listOf(1000L, 2500L), result.map { it.startMs })
        assertEquals(listOf("First & second", "第二句"), result.map { it.text })
    }
    @Test fun plainLyricsNeverGetInventedSeekTimesAndEmptyDataIsSupported() {
        assertTrue(AppleLyricsSource.copyLines(Song(0, listOf(Line(0, "Untimed")))).isEmpty())
        assertTrue(AppleLyricsSource.copyLines(Song(2, emptyList())).isEmpty())
    }
    class Song(private val timing: Long, lines: List<Line>) {
        private val sections = Vector(listOf(Pointer(Section(lines))))
        fun getTiming() = timing
        fun getSections() = sections
    }
    class Pointer(private val value: Any) { fun get() = value }
    class Vector(private val values: List<Any>) {
        fun size() = values.size.toLong()
        fun get(index: Long) = values[index.toInt()]
    }
    class Section(lines: List<Line>) { private val lines = Vector(lines.map { Pointer(it) }); fun getLines() = lines }
    class Line(private val time: Int, private val html: String) { fun getBegin() = time; fun getHtmlLineText() = html }
}
