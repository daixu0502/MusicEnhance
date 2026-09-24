package com.jaco.musicenhance.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.artwork.PlayerArtworkResolver
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerDisplayState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Coordinates adapter-owned providers and publishes ready display state, independent of any view. */
internal class EnhancedPlayerController(private val session: PlayerSession) : PlayerController {
    override val appName get() = session.appName
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = linkedSetOf<(PlayerDisplayState) -> Unit>()
    private var artworkResolver = PlayerArtworkResolver(session.artwork, session.data::nativeArtwork)
    private var state = PlayerDisplayState()
    private var controls = PlayerControlState()
    private var nextControlPollAtMs = 0L
    @Volatile private var active = false
    @Volatile private var released = false
    private var lyricsRequested = false
    private var spectrumPlaying: Boolean? = null
    // Read the latest source state instead of replaying an older queued notification after a seek/skip.
    private val sourceListener: (PlayerSnapshot) -> Unit = { scheduleUpdate() }
    private val update = object : Runnable {
        override fun run() {
            if (!active || released) return
            publishState()
            if (active && !released) {
                handler.removeCallbacks(this)
                handler.postDelayed(this, STATE_UPDATE_MS)
            }
        }
    }

    override fun addListener(listener: (PlayerDisplayState) -> Unit) {
        if (released) return
        listeners += listener
        listener(state)
    }

    override fun removeListener(listener: (PlayerDisplayState) -> Unit) { listeners -= listener }

    override fun setActive(active: Boolean) {
        if (released || this.active == active) return
        this.active = active
        handler.removeCallbacks(update)
        if (active) {
            nextControlPollAtMs = 0L
            session.data.addListener(sourceListener)
            // addListener may synchronously notify; keep only one refresh chain.
            scheduleUpdate()
        } else {
            session.data.removeListener(sourceListener)
        }
    }

    override fun setLyricsRequested(requested: Boolean) {
        if (released || lyricsRequested == requested) return
        lyricsRequested = requested
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (!active || released) return
        handler.removeCallbacks(update)
        handler.post(update)
    }

    private fun publishState() {
        val snapshot = session.data.snapshot()
        val nowMs = SystemClock.elapsedRealtime()
        val trackChanged = state.trackKey != snapshot.metadataKey
        if (trackChanged || nowMs >= nextControlPollAtMs) {
            controls = session.data.controlState()
            nextControlPollAtMs = nowMs + CONTROL_POLL_MS
        }
        val resolvedArtwork = artworkResolver.resolve(snapshot, nowMs)
        val lyrics = when {
            lyricsRequested -> session.lyrics.snapshot(snapshot)
            trackChanged -> LyricsSnapshot(snapshot.metadataKey, LyricsStatus.LOADING)
            else -> state.lyrics
        }
        // Optional state is unknown until the adapter can associate it with this song.
        val controlsMatch = controls.songTitle == snapshot.title
        val matchedControls = controls.copy(
            favorite = controls.favorite.takeIf { controlsMatch },
            favoritePending = controls.favoritePending && controlsMatch,
        )
        if (matchedControls != state.controls) {
            moduleInfo("$appName native controls: repeat=${matchedControls.repeatMode}, favorite=${matchedControls.favorite}, favoritePending=${matchedControls.favoritePending}")
        }
        if (resolvedArtwork.background !== state.artwork) {
            resolvedArtwork.background?.let { moduleInfo("Player artwork selected: ${it.width}x${it.height}") }
        }
        state = PlayerDisplayState(
            trackKey = snapshot.metadataKey, title = snapshot.title, artist = snapshot.artist,
            durationMs = snapshot.durationMs, positionMs = snapshot.positionMs, isPlaying = snapshot.isPlaying,
            artwork = resolvedArtwork.background, thumbnail = resolvedArtwork.thumbnail,
            controls = matchedControls, lyrics = lyrics,
        )
        if (spectrumPlaying != snapshot.isPlaying) {
            spectrumPlaying = snapshot.isPlaying
            session.actions.setSpectrumPlaybackActive(snapshot.isPlaying)
        }
        listeners.toList().forEach { it(state) }
    }

    override fun bassLevel() = if (active && !released) session.data.bassLevel() else 0f
    override fun playPause() { if (!released) session.actions.playPause() }
    override fun previous() { if (!released) session.actions.previous() }
    override fun next() { if (!released) session.actions.next() }
    override fun seekTo(positionMs: Long, trackKey: String) = seek(positionMs, trackKey, session.actions.seekTo)
    override fun seekAndPlay(positionMs: Long, trackKey: String) = seek(positionMs, trackKey, session.actions.seekAndPlay)

    private fun seek(positionMs: Long, trackKey: String, action: (Long) -> Unit) {
        if (released) return
        val current = session.data.snapshot()
        if (current.metadataKey != trackKey || current.durationMs <= 0) return
        action(positionMs.coerceIn(0, current.durationMs))
        scheduleUpdate()
    }

    override fun cycleRepeat(): Boolean = changeControl(session.actions.cycleRepeat)
    override fun toggleFavorite(): Boolean = changeControl(session.actions.toggleFavorite)

    private fun changeControl(action: () -> Boolean): Boolean {
        if (released || !action()) return false
        nextControlPollAtMs = 0L
        scheduleUpdate()
        return true
    }

    override fun release() {
        if (released) return
        try { setActive(false) } finally {
            released = true
            listeners.clear()
            state = PlayerDisplayState()
            artworkResolver = PlayerArtworkResolver(session.artwork, session.data::nativeArtwork)
            try { session.onRelease() } finally {
                try { session.lyrics.release() } finally { session.artwork.release() }
            }
        }
    }

    private companion object {
        const val STATE_UPDATE_MS = 50L
        const val CONTROL_POLL_MS = 200L
    }
}
