package com.jaco.musicenhance.adapter.spotify

import android.os.Bundle
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode

internal class SpotifyControlSource {
    private var reportedFailure = false
    private var pendingFavorite: Pair<String, Boolean>? = null
    private var pendingUntilMs = 0L
    private var repeatPendingUntilMs = 0L
    private var pendingRepeat: RepeatMode? = null

    fun read(player: PlayerSnapshot): PlayerControlState = guarded(PlayerControlState()) {
        val state = SpotifyApi.current(player) ?: return@guarded PlayerControlState()
        val song = state.song ?: return@guarded PlayerControlState()
        val favorite = song.metadata["collection.in_collection"]?.toBooleanStrictOrNull()
        val pending = pendingFavorite
        if (pending != null && (pending.first != song.uri || pending.second == favorite ||
                SystemClock.elapsedRealtime() >= pendingUntilMs)) pendingFavorite = null
        if (pendingRepeat == state.repeatMode || SystemClock.elapsedRealtime() >= repeatPendingUntilMs) pendingRepeat = null
        PlayerControlState(state.repeatMode, favorite, player.title, pendingFavorite != null)
    }

    fun cycleRepeat(): Boolean = guarded(false) {
        val state = SpotifyApi.current(MediaSessionDataSource.snapshot()) ?: return@guarded false
        if (pendingRepeat != null && SystemClock.elapsedRealtime() < repeatPendingUntilMs) return@guarded false
        val target = nextMode(state) ?: return@guarded false
        val transport = SpotifyPlayerHooks.mediaSession.get()?.controller?.transportControls ?: return@guarded false
        fun send(name: String, mode: Int) {
            transport.sendCustomAction("android.support.v4.media.session.action.SET_${name}_MODE", Bundle().apply {
                putInt("android.support.v4.media.session.action.ARGUMENT_${name}_MODE", mode)
            })
        }
        // These are handled by Spotify's p.zpf0 -> aqf0 callback, including account restrictions.
        if ((state.repeatMode == RepeatMode.SHUFFLE) != (target == RepeatMode.SHUFFLE)) {
            send("SHUFFLE", if (target == RepeatMode.SHUFFLE) 1 else 0)
        }
        send("REPEAT", when (target) { RepeatMode.LIST_LOOP -> 2; RepeatMode.SINGLE_LOOP -> 1; else -> 0 })
        pendingRepeat = target
        repeatPendingUntilMs = SystemClock.elapsedRealtime() + 2_000L
        true
    }

    fun toggleFavorite(): Boolean = guarded(false) {
        val player = MediaSessionDataSource.snapshot()
        val song = SpotifyApi.current(player)?.song ?: return@guarded false
        val controls = read(player)
        if (controls.favoritePending || song.metadata["collection.can_add"] == "false") return@guarded false
        val favorite = controls.favorite ?: return@guarded false
        val action = if (favorite) SpotifyPlayerHooks.removeFavorite else SpotifyPlayerHooks.addFavorite
        val saveUri = song.metadata["save_track.uri"]?.takeIf { it.isNotBlank() } ?: song.uri
        if (action == null || action.uri != saveUri || action.favorite == favorite) return@guarded false
        action.intent.send()
        pendingFavorite = song.uri to !favorite
        pendingUntilMs = SystemClock.elapsedRealtime() + 3_000L
        true
    }

    private inline fun <T> guarded(fallback: T, action: () -> T): T = try { action() } catch (error: Exception) {
        if (!reportedFailure) {
            reportedFailure = true
            moduleInfo("Spotify control unavailable: ${error.cause ?: error}")
        }
        fallback
    }

    companion object {
        fun nextMode(state: SpotifyApi.State): RepeatMode? {
            // Changing shuffle and repeat are separate commands. Do not alter locked options.
            val order = listOf(RepeatMode.SEQUENTIAL, RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP, RepeatMode.SHUFFLE)
            val index = order.indexOf(state.repeatMode)
            if (index < 0 || !state.repeatContextAllowed || !state.repeatTrackAllowed) return null
            return (1..3).map { order[(index + it) % order.size] }.firstOrNull {
                (it != RepeatMode.SHUFFLE && state.repeatMode != RepeatMode.SHUFFLE) || state.shuffleAllowed
            }
        }
    }
}
