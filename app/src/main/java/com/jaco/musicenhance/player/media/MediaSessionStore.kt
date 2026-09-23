package com.jaco.musicenhance.player.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.CopyOnWriteArraySet

internal object MediaSessionStore {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(PlayerSnapshot) -> Unit>()
    private var controller: MediaController? = null
    private var lastSnapshot = PlayerSnapshot.Empty
    private var lastSnapshotReceivedAt = SystemClock.elapsedRealtime()

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onSessionDestroyed() {
            controller = null
            lastSnapshot = PlayerSnapshot.Empty
            lastSnapshotReceivedAt = SystemClock.elapsedRealtime()
            notifyListeners(lastSnapshot)
            PlayerProcessBridge.publishSnapshot(lastSnapshot)
        }
    }

    fun connect(session: MediaSession?) {
        if (session == null) return
        mainHandler.post {
            val next = runCatching { session.controller }.getOrNull() ?: return@post
            if (controller?.sessionToken != next.sessionToken) {
                runCatching { controller?.unregisterCallback(callback) }
                controller = next
                runCatching { next.registerCallback(callback, mainHandler) }
            }
            publish()
        }
    }

    fun addListener(listener: (PlayerSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun removeListener(listener: (PlayerSnapshot) -> Unit) {
        listeners -= listener
    }

    fun playPause() {
        val current = controller ?: return PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_PLAY_PAUSE)
        if (current.playbackState?.state == PlaybackState.STATE_PLAYING) {
            current.transportControls.pause()
        } else {
            current.transportControls.play()
        }
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
            ?: PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_PREVIOUS)
    }

    fun next() {
        controller?.transportControls?.skipToNext()
            ?: PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_NEXT)
    }

    fun seekTo(positionMs: Long) {
        val current = controller
        if (current != null) current.transportControls.seekTo(positionMs.coerceAtLeast(0))
        else PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_SEEK, positionMs)
    }

    fun seekAndPlay(positionMs: Long) {
        val current = controller
        if (current != null) {
            current.transportControls.seekTo(positionMs.coerceAtLeast(0))
            current.transportControls.play()
        } else {
            PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_SEEK_AND_PLAY, positionMs)
        }
    }

    fun repeat(): Boolean {
        if (controller != null) return performCustomAction("repeat", "cycle", "loop", "mode", "循环", "单曲")
        PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_REPEAT)
        return true
    }

    fun favorite(): Boolean {
        if (controller != null) {
            return performCustomAction(
                "favorite", "favourite", "collect", "like", "love", "star", "fav", "收藏", "喜欢",
            )
        }
        PlayerProcessBridge.sendCommand(PlayerProcessBridge.COMMAND_FAVORITE)
        return true
    }

    fun acceptRemoteSnapshot(snapshot: PlayerSnapshot) {
        if (controller != null) return
        lastSnapshot = snapshot
        lastSnapshotReceivedAt = SystemClock.elapsedRealtime()
        if (!snapshot.isPlaying) SpectrumEngine.clearRemoteTimeline()
        notifyListeners(snapshot)
    }

    fun executeRemoteCommand(command: String, positionMs: Long) {
        val current = controller ?: return
        when (command) {
            PlayerProcessBridge.COMMAND_PLAY_PAUSE -> {
                if (current.playbackState?.state == PlaybackState.STATE_PLAYING) current.transportControls.pause()
                else current.transportControls.play()
            }
            PlayerProcessBridge.COMMAND_PREVIOUS -> current.transportControls.skipToPrevious()
            PlayerProcessBridge.COMMAND_NEXT -> current.transportControls.skipToNext()
            PlayerProcessBridge.COMMAND_SEEK -> current.transportControls.seekTo(positionMs.coerceAtLeast(0))
            PlayerProcessBridge.COMMAND_SEEK_AND_PLAY -> seekAndPlay(positionMs)
            PlayerProcessBridge.COMMAND_REPEAT -> performCustomAction(
                "repeat", "cycle", "loop", "mode", "循环", "单曲",
            )
            PlayerProcessBridge.COMMAND_FAVORITE -> performCustomAction(
                "favorite", "favourite", "collect", "like", "love", "star", "fav", "收藏", "喜欢",
            )
        }
    }

    fun republishLocalSnapshot() {
        if (controller != null) PlayerProcessBridge.publishSnapshot(snapshot())
    }

    fun snapshot(): PlayerSnapshot {
        val current = controller ?: return extrapolatedRemoteSnapshot()
        val metadata = current.metadata
        val playback = current.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        var position = playback?.position?.coerceAtLeast(0) ?: 0
        if (playback?.state == PlaybackState.STATE_PLAYING) {
            val elapsed = (SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime).coerceAtLeast(0)
            position += (elapsed * playback.playbackSpeed).toLong()
        }
        if (duration > 0) position = position.coerceAtMost(duration)

        return PlayerSnapshot(
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE, "打开音乐应用开始播放"),
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST, "未知歌手"),
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM, ""),
            artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            durationMs = duration,
            positionMs = position,
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING ||
                playback?.state == PlaybackState.STATE_BUFFERING,
            actions = playback?.actions ?: 0,
            customActions = playback?.customActions?.map { it.action }.orEmpty(),
        ).also {
            lastSnapshot = it
            lastSnapshotReceivedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun extrapolatedRemoteSnapshot(): PlayerSnapshot {
        val snapshot = lastSnapshot
        if (!snapshot.isPlaying || snapshot.durationMs <= 0L) return snapshot
        val elapsed = (SystemClock.elapsedRealtime() - lastSnapshotReceivedAt).coerceAtLeast(0L)
        return snapshot.copy(
            positionMs = (snapshot.positionMs + elapsed).coerceAtMost(snapshot.durationMs),
        )
    }

    private fun performCustomAction(vararg hints: String): Boolean {
        val action = controller?.playbackState?.customActions?.firstOrNull { item ->
            val labels = listOf(item.action, item.name?.toString().orEmpty())
            hints.any { hint -> labels.any { it.contains(hint, ignoreCase = true) } }
        } ?: return false
        controller?.transportControls?.sendCustomAction(action, action.extras)
        return true
    }

    private fun publish() {
        val value = snapshot()
        notifyListeners(value)
        PlayerProcessBridge.publishSnapshot(value)
    }

    private fun notifyListeners(value: PlayerSnapshot) {
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private fun MediaMetadata?.text(key: String, fallback: String): String {
        val value = this?.getText(key)?.toString()?.trim()
        return value?.takeIf { it.isNotEmpty() } ?: fallback
    }
}
