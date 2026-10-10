package com.jaco.musicenhance.adapter.lxx

import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.lyrics.LrcLyricsParser
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Only the current host-provided lyric is retained; no file lookup, search or download. */
internal class LxxLyricsProvider(private val currentSongId: () -> String?) : LyricsProvider {
    override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
        val state = LxxStateBridge.state
        val matches = state.id.isNotBlank() && state.id == currentSongId() && state.metadataKey == player.metadataKey
        return if (matches) LyricsSnapshot(state.id, if (state.lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, state.lines)
        else LyricsSnapshot(player.metadataKey, LyricsStatus.UNAVAILABLE)
    }

    override fun release() = Unit // The process bridge belongs to RN, not this screen.

    companion object {
        internal fun parse(text: String): List<LyricLine> = LrcLyricsParser.parse(text)
    }
}
