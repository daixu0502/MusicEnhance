package com.jaco.musicenhance.player.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Only a visible enhanced UI receives data; service/widget processes receive small control messages. */
internal object PlayerProcessBridge {
    private const val PREFIX = "com.jaco.musicenhance.action."
    private const val ACTION_OBSERVE = PREFIX + "OBSERVE_PLAYER"
    private const val ACTION_REQUEST_OBSERVERS = PREFIX + "REQUEST_PLAYER_OBSERVERS"
    private const val ACTION_COMMAND = PREFIX + "PLAYER_COMMAND"
    private const val ACTION_SNAPSHOT = PREFIX + "PLAYER_SNAPSHOT."
    private const val ACTION_SPECTRUM = PREFIX + "PLAYER_SPECTRUM."
    private const val ACTION_ACK = PREFIX + "PLAYER_ACK."
    private const val EXTRA_CLIENT = "client"
    private const val EXTRA_ACTIVE = "active"
    private const val EXTRA_SOURCE = "source"
    private const val EXTRA_SEQUENCE = "sequence"
    private const val EXTRA_CAPTURED_AT_MS = "captured_at_ms"
    private const val EXTRA_SPECTRUM = "spectrum"
    private const val EXTRA_COMMAND = "command"
    private const val EXTRA_POSITION = "position"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_ARTIST = "artist"
    private const val EXTRA_ALBUM = "album"
    private const val EXTRA_ARTWORK_FILE = "artwork_file"
    private const val EXTRA_DURATION = "duration"
    private const val EXTRA_PLAYING = "playing"
    private const val EXTRA_REPEAT_MODE = "repeat_mode"
    private const val EXTRA_FAVORITE = "favorite"
    private const val EXTRA_CONTROL_TITLE = "control_title"
    private const val EXTRA_SPECTRUM_FRAMES = "spectrum_frames"
    private const val EXTRA_SPECTRUM_FRAME_MS = "spectrum_frame_ms"
    private const val EXTRA_SPECTRUM_START_NANOS = "spectrum_start_nanos"
    private const val EXTRA_SPECTRUM_STREAM_ID = "spectrum_stream_id"
    private const val EXTRA_SPECTRUM_CLEAR = "spectrum_clear"
    private const val SNAPSHOT_INTERVAL_MS = 250L
    private const val RENEW_INTERVAL_MS = 5_000L
    private const val MAX_SPECTRUM_FRAMES = 200
    private val clientIdPattern = Regex("[0-9a-f-]{36}")

    const val COMMAND_PLAY_PAUSE = "play_pause"
    const val COMMAND_PREVIOUS = "previous"
    const val COMMAND_NEXT = "next"
    const val COMMAND_SEEK = "seek"
    const val COMMAND_SEEK_AND_PLAY = "seek_and_play"
    const val COMMAND_REPEAT = "repeat"
    const val COMMAND_FAVORITE = "favorite"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy {
        Handler(HandlerThread("MusicEnhance-PlayerBridge").apply { start() }.looper)
    }
    private val clients = PlayerBridgeClients()
    private val spectrumSequence = AtomicLong()
    private val snapshotLock = Any()
    private var snapshotRevision = 0L
    private var latestPublication: Publication? = null
    private var snapshotScheduled = false
    private var lastSnapshotAtMs = -SNAPSHOT_INTERVAL_MS
    private var artwork: PlayerBridgeArtwork? = null
    @Volatile private var processApplication: Application? = null
    @Volatile private var observerId: String? = null
    private var remoteArtworkName: String? = null
    private var remoteTrackKey: String? = null
    private var artworkRequest = 0L
    private var pendingArtworkRead: Runnable? = null

    private data class Publication(
        val revision: Long,
        val snapshot: PlayerSnapshot,
        val capturedAtMs: Long = SystemClock.elapsedRealtime(),
    )

    private val renewObservation = object : Runnable {
        override fun run() {
            val id = observerId ?: return
            announceObservation(id, true)
            main.postDelayed(this, RENEW_INTERVAL_MS)
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_OBSERVE -> {
                    val id = intent.getStringExtra(EXTRA_CLIENT) ?: return
                    if (!clientIdPattern.matches(id) || id == observerId) return
                    val active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
                    clients.observe(id, active, SystemClock.elapsedRealtime())
                    if (active) MediaSessionStore.republishLocalSnapshot()
                }
                ACTION_REQUEST_OBSERVERS -> observerId?.let { announceObservation(it, true) }
                ACTION_COMMAND -> MediaSessionStore.executeRemoteCommand(
                    intent.getStringExtra(EXTRA_COMMAND).orEmpty(),
                    intent.getLongExtra(EXTRA_POSITION, 0L),
                )
                ACTION_ACK + Process.myPid() -> {
                    val id = intent.getStringExtra(EXTRA_CLIENT) ?: return
                    val spectrum = intent.getBooleanExtra(EXTRA_SPECTRUM, false)
                    clients.acknowledge(id, intent.getLongExtra(EXTRA_SEQUENCE, 0L), spectrum)
                    if (!spectrum) scheduleSnapshot()
                }
            }
        }
    }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = observerId ?: return
            val spectrum = intent.action == ACTION_SPECTRUM + id
            if (!spectrum && intent.action != ACTION_SNAPSHOT + id) return
            // Only one outstanding packet per channel: a blocked/frozen UI cannot fill Binder.
            sendAcknowledgement(id, intent, spectrum)
            if (!spectrum) receiveSnapshot(intent, id)
            else if (intent.getBooleanExtra(EXTRA_SPECTRUM_CLEAR, false)) {
                SpectrumEngine.clearRemoteStream(intent.getLongExtra(EXTRA_SPECTRUM_STREAM_ID, 0L))
            } else {
                val frames = intent.getFloatArrayExtra(EXTRA_SPECTRUM_FRAMES) ?: return
                if (frames.size > MAX_SPECTRUM_FRAMES) return
                SpectrumEngine.acceptRemote(frames,
                    intent.getIntExtra(EXTRA_SPECTRUM_FRAME_MS, SpectrumEngine.FRAME_DURATION_MS),
                    intent.getLongExtra(EXTRA_SPECTRUM_START_NANOS, SystemClock.elapsedRealtimeNanos()),
                    intent.getLongExtra(EXTRA_SPECTRUM_STREAM_ID, 0L))
            }
        }
    }

    fun initialize(application: Application) {
        if (processApplication != null) return
        application.registerReceiver(controlReceiver, IntentFilter().apply {
            addAction(ACTION_OBSERVE)
            addAction(ACTION_REQUEST_OBSERVERS)
            addAction(ACTION_COMMAND)
            addAction(ACTION_ACK + Process.myPid())
        }, Context.RECEIVER_NOT_EXPORTED)
        artwork = PlayerBridgeArtwork(File(application.cacheDir, "musicenhance-player-bridge"))
        processApplication = application
        moduleInfo("Music app process bridge ready; process=${Application.getProcessName()}")
        // A playback service can restart while an enhanced UI remains visible.
        send(Intent(ACTION_REQUEST_OBSERVERS))
    }

    /** First/last UI listener transitions run on main. */
    fun setObserving(active: Boolean) {
        val application = processApplication ?: return
        if (active == (observerId != null)) return
        if (active) {
            val id = UUID.randomUUID().toString()
            val registered = runCatching {
                application.registerReceiver(dataReceiver, IntentFilter().apply {
                    addAction(ACTION_SNAPSHOT + id)
                    addAction(ACTION_SPECTRUM + id)
                }, Context.RECEIVER_NOT_EXPORTED)
            }.onFailure { moduleInfo("Player data subscription failed: $it") }.isSuccess
            if (!registered) return
            observerId = id
            renewObservation.run()
        } else {
            val id = observerId ?: return
            observerId = null
            main.removeCallbacks(renewObservation)
            runCatching { application.unregisterReceiver(dataReceiver) }
                .onFailure { moduleInfo("Player data subscription cleanup failed: $it") }
            announceObservation(id, false)
            artworkRequest++
            pendingArtworkRead?.let(worker::removeCallbacks)
            pendingArtworkRead = null
            remoteArtworkName = null
            remoteTrackKey = null
        }
    }

    fun hasRemoteConsumers() = clients.hasClients(SystemClock.elapsedRealtime())
    fun hasSpectrumConsumers() = observerId != null || hasRemoteConsumers()

    private fun announceObservation(id: String, active: Boolean) {
        send(Intent(ACTION_OBSERVE).putExtra(EXTRA_CLIENT, id).putExtra(EXTRA_ACTIVE, active))
    }

    fun publishSnapshot(snapshot: PlayerSnapshot) {
        if (!hasRemoteConsumers()) return
        synchronized(snapshotLock) {
            latestPublication = Publication(++snapshotRevision, snapshot)
        }
        scheduleSnapshot()
    }

    private fun scheduleSnapshot() {
        synchronized(snapshotLock) {
            val publication = latestPublication ?: return
            if (snapshotScheduled || !clients.needsSnapshot(publication.revision, SystemClock.elapsedRealtime())) return
            snapshotScheduled = true
            val delayMs = (lastSnapshotAtMs + SNAPSHOT_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            worker.postDelayed(publishLatestSnapshot, delayMs)
        }
    }

    private val publishLatestSnapshot = Runnable {
        val publication = synchronized(snapshotLock) {
            snapshotScheduled = false
            lastSnapshotAtMs = SystemClock.elapsedRealtime()
            latestPublication
        }
        if (publication != null && clients.needsSnapshot(publication.revision, SystemClock.elapsedRealtime())) {
            runCatching {
                val name = runCatching {
                    artwork?.write(publication.snapshot.metadataKey, publication.snapshot.artwork)
                }.onFailure { moduleInfo("Player snapshot artwork write failed: $it") }.getOrNull()
                // Encoding must not send an outgoing song over newer metadata.
                val current = synchronized(snapshotLock) { latestPublication }
                if (current != null && current.snapshot.metadataKey == publication.snapshot.metadataKey &&
                    current.snapshot.artwork === publication.snapshot.artwork) {
                    clients.claimSnapshot(current.revision, SystemClock.elapsedRealtime()).forEach { id ->
                        val snapshot = current.snapshot
                        send(Intent(ACTION_SNAPSHOT + id).apply {
                            putExtra(EXTRA_SOURCE, Process.myPid())
                            putExtra(EXTRA_SEQUENCE, current.revision)
                            putExtra(EXTRA_CAPTURED_AT_MS, current.capturedAtMs)
                            putExtra(EXTRA_TITLE, snapshot.title)
                            putExtra(EXTRA_ARTIST, snapshot.artist)
                            putExtra(EXTRA_ALBUM, snapshot.album)
                            putExtra(EXTRA_DURATION, snapshot.durationMs)
                            putExtra(EXTRA_POSITION, snapshot.positionMs)
                            putExtra(EXTRA_PLAYING, snapshot.isPlaying)
                            putExtra(EXTRA_ARTWORK_FILE, name)
                            putExtra(EXTRA_REPEAT_MODE, snapshot.controls.repeatMode.name)
                            snapshot.controls.favorite?.let { putExtra(EXTRA_FAVORITE, it) }
                            putExtra(EXTRA_CONTROL_TITLE, snapshot.controls.songTitle)
                        })
                    }
                }
            }.onFailure { moduleInfo("Player snapshot publication failed: $it") }
            scheduleSnapshot()
        }
    }

    fun sendCommand(command: String, positionMs: Long = 0L) {
        send(Intent(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command).putExtra(EXTRA_POSITION, positionMs))
    }

    fun publishSpectrum(frames: FloatArray, frameDurationMs: Int, startAtNanos: Long, streamId: Long) {
        if (frames.isEmpty()) return
        if (observerId != null) SpectrumEngine.acceptRemote(frames, frameDurationMs, startAtNanos, streamId)
        val skipped = (frames.size - MAX_SPECTRUM_FRAMES).coerceAtLeast(0)
        val boundedFrames = if (skipped > 0) frames.copyOfRange(skipped, frames.size) else frames
        sendSpectrum {
            putExtra(EXTRA_SPECTRUM_FRAMES, boundedFrames)
            putExtra(EXTRA_SPECTRUM_FRAME_MS, frameDurationMs)
            putExtra(EXTRA_SPECTRUM_START_NANOS, startAtNanos + skipped * frameDurationMs * 1_000_000L)
            putExtra(EXTRA_SPECTRUM_STREAM_ID, streamId)
        }
    }

    fun clearSpectrum(streamId: Long) {
        if (observerId != null) SpectrumEngine.clearRemoteStream(streamId)
        sendSpectrum {
            putExtra(EXTRA_SPECTRUM_STREAM_ID, streamId)
            putExtra(EXTRA_SPECTRUM_CLEAR, true)
        }
    }

    private fun sendSpectrum(extras: Intent.() -> Unit) {
        val sequence = spectrumSequence.incrementAndGet()
        clients.claimSpectrum(sequence, SystemClock.elapsedRealtime()).forEach { id ->
            send(Intent(ACTION_SPECTRUM + id).apply {
                putExtra(EXTRA_SOURCE, Process.myPid())
                putExtra(EXTRA_SEQUENCE, sequence)
                extras()
            })
        }
    }

    private fun sendAcknowledgement(id: String, intent: Intent, spectrum: Boolean) {
        val source = intent.getIntExtra(EXTRA_SOURCE, 0).takeIf { it > 0 } ?: return
        send(Intent(ACTION_ACK + source).putExtra(EXTRA_CLIENT, id)
            .putExtra(EXTRA_SEQUENCE, intent.getLongExtra(EXTRA_SEQUENCE, 0L))
            .putExtra(EXTRA_SPECTRUM, spectrum))
    }

    private fun receiveSnapshot(intent: Intent, id: String) {
        if (MediaSessionStore.hasLocalSession()) return
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
        val durationMs = intent.getLongExtra(EXTRA_DURATION, 0L).coerceAtLeast(0)
        val elapsedMs = if (playing) (SystemClock.elapsedRealtime() -
            intent.getLongExtra(EXTRA_CAPTURED_AT_MS, SystemClock.elapsedRealtime())).coerceAtLeast(0) else 0L
        val positionMs = (intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0) + elapsedMs)
            .let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        val snapshot = PlayerSnapshot(
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
            album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
            artwork = null,
            durationMs = durationMs,
            positionMs = positionMs,
            isPlaying = playing,
            controls = PlayerControlState(
                repeatMode = RepeatMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_REPEAT_MODE) }
                    ?: RepeatMode.UNKNOWN,
                favorite = if (intent.hasExtra(EXTRA_FAVORITE)) intent.getBooleanExtra(EXTRA_FAVORITE, false) else null,
                songTitle = intent.getStringExtra(EXTRA_CONTROL_TITLE),
            ),
        )
        val name = intent.getStringExtra(EXTRA_ARTWORK_FILE)
        val sameArtwork = snapshot.metadataKey == remoteTrackKey && name == remoteArtworkName
        val previousArtwork = MediaSessionStore.snapshot().artwork.takeIf { sameArtwork }
        MediaSessionStore.acceptRemoteSnapshot(snapshot.copy(artwork = previousArtwork))
        if (sameArtwork && (previousArtwork != null || pendingArtworkRead != null)) return
        remoteTrackKey = snapshot.metadataKey
        remoteArtworkName = name
        val request = ++artworkRequest
        pendingArtworkRead?.let(worker::removeCallbacks)
        pendingArtworkRead = null
        if (name == null) return
        val read = Runnable {
            val bitmap = runCatching { artwork?.read(name) }
                .onFailure { moduleInfo("Player snapshot artwork read failed: $it") }.getOrNull()
            main.post {
                if (observerId == id && artworkRequest == request) {
                    pendingArtworkRead = null
                    bitmap?.let { MediaSessionStore.updateRemoteArtwork(snapshot.metadataKey, it) }
                }
            }
        }
        pendingArtworkRead = read
        worker.post(read)
    }

    private fun send(intent: Intent) {
        val application = processApplication ?: return
        runCatching { application.sendBroadcast(intent.setPackage(application.packageName)) }
            .onFailure { moduleInfo("Player process bridge ${intent.action} failed: $it") }
    }
}
