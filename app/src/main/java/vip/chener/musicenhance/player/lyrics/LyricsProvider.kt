package vip.chener.musicenhance.player.lyrics

import vip.chener.musicenhance.player.model.LyricsSnapshot
import vip.chener.musicenhance.player.model.LyricsStatus
import vip.chener.musicenhance.player.model.PlayerSnapshot

/**
 * App-specific lyrics source. Called on the UI thread: return cached data immediately and
 * schedule slow work elsewhere. Results must identify their track and use media time in ms.
 * release() invalidates pending callbacks; the provider may be reused after reattachment.
 */
internal interface LyricsProvider {
    fun snapshot(player: PlayerSnapshot): LyricsSnapshot
    fun release()

    object Unsupported : LyricsProvider {
        private val unsupported = LyricsSnapshot("", LyricsStatus.UNSUPPORTED)
        override fun snapshot(player: PlayerSnapshot) = unsupported
        override fun release() = Unit
    }
}
