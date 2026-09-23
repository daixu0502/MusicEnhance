package com.jaco.musicenhance.player.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimelineTest {
    @Test fun introAndExactSentenceBoundaries() {
        val lines = listOf(LyricLine(1000, "第一句"), LyricLine(3200, "第二句"))
        assertEquals(-1, LyricTimeline.activeIndex(lines, 999))
        assertEquals(0, LyricTimeline.activeIndex(lines, 1000))
        assertEquals(0, LyricTimeline.activeIndex(lines, 3199))
        assertEquals(1, LyricTimeline.activeIndex(lines, 3200))
        assertEquals(1, LyricTimeline.activeIndex(lines, 100_000))
    }

    @Test fun simultaneousLinesMergeAndDuplicateTranslationsAreRemoved() {
        val lines = LyricTimeline.normalize(listOf(
            LyricLine(2000, "后一句"), LyricLine(1000, " 原文 "),
            LyricLine(1000, "翻译"), LyricLine(1000, "原文"),
        ))
        assertEquals(listOf(LyricLine(1000, "原文\n翻译"), LyricLine(2000, "后一句")), lines)
    }

    @Test fun offsetUsesMediaTimelineForBothFollowingAndSeeking() {
        val lines = LyricTimeline.normalize(listOf(LyricLine(1000, "偏移后的歌词")), 350)
        assertEquals(1350L, lines.single().startMs)
        assertEquals(-1, LyricTimeline.activeIndex(lines, 1349))
        assertEquals(0, LyricTimeline.activeIndex(lines, lines.single().startMs))
    }

    @Test fun negativeOffsetClampsAndMergesAtStartOfTrack() {
        assertEquals(listOf(LyricLine(0, "一\n二")), LyricTimeline.normalize(
            listOf(LyricLine(0, "一"), LyricLine(100, "二")), -200,
        ))
    }

    @Test fun missingAndInvalidLyricsDoNotInventSeekTargets() {
        val lines = LyricTimeline.normalize(listOf(LyricLine(-1, "未计时"), LyricLine(100, "   ")))
        assertEquals(emptyList<LyricLine>(), lines)
        assertEquals(-1, LyricTimeline.activeIndex(lines, 1500))
    }

    @Test fun seekingBackwardRecomputesCurrentLine() {
        val lines = (0..100).map { LyricLine(it * 1500L, "歌词 $it") }
        assertEquals(99, LyricTimeline.activeIndex(lines, 149_000))
        assertEquals(2, LyricTimeline.activeIndex(lines, 3000))
        assertEquals(0, LyricTimeline.activeIndex(lines, 0))
    }
}
