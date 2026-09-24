package com.jaco.musicenhance.player.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import com.jaco.musicenhance.hook.MusicEnhanceModule
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.RepeatMode
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Shares player state and commands between a registered music app's UI and service processes. */
internal object PlayerProcessBridge {
    private const val ACTION_SNAPSHOT = "com.jaco.musicenhance.action.PLAYER_SNAPSHOT"
    private const val ACTION_COMMAND = "com.jaco.musicenhance.action.PLAYER_COMMAND"
    private const val ACTION_SPECTRUM = "com.jaco.musicenhance.action.PLAYER_SPECTRUM"
    private const val ACTION_REQUEST_STATE = "com.jaco.musicenhance.action.REQUEST_PLAYER_STATE"
    private const val EXTRA_COMMAND = "command"
    private const val EXTRA_POSITION = "position"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_ARTIST = "artist"
    private const val EXTRA_ALBUM = "album"
    private const val EXTRA_ARTWORK = "artwork"
    private const val EXTRA_DURATION = "duration"
    private const val EXTRA_PLAYING = "playing"
    private const val EXTRA_ACTIONS = "actions"
    private const val EXTRA_CUSTOM_ACTIONS = "custom_actions"
    private const val EXTRA_REPEAT_MODE = "repeat_mode"
    private const val EXTRA_FAVORITE = "favorite"
    private const val EXTRA_CONTROL_TITLE = "control_title"
    private const val EXTRA_SPECTRUM_FRAMES = "spectrum_frames"
    private const val EXTRA_SPECTRUM_FRAME_MS = "spectrum_frame_ms"
    private const val MAX_ARTWORK_SIDE = 1_280
    private const val FALLBACK_ARTWORK_SIDE = 960
    private const val MAX_ARTWORK_BYTES = 800_000

    const val COMMAND_PLAY_PAUSE = "play_pause"
    const val COMMAND_PREVIOUS = "previous"
    const val COMMAND_NEXT = "next"
    const val COMMAND_SEEK = "seek"
    const val COMMAND_SEEK_AND_PLAY = "seek_and_play"
    const val COMMAND_REPEAT = "repeat"
    const val COMMAND_FAVORITE = "favorite"

    @Volatile
    private var processApplication: Application? = null

    fun initialize(application: Application) {
        if (processApplication != null) return
        val appContext = application
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SNAPSHOT -> receiveSnapshot(intent)
                    ACTION_COMMAND -> MediaSessionStore.executeRemoteCommand(
                        intent.getStringExtra(EXTRA_COMMAND).orEmpty(),
                        intent.getLongExtra(EXTRA_POSITION, 0L),
                    )
                    ACTION_SPECTRUM -> SpectrumEngine.acceptRemote(
                        intent.getFloatArrayExtra(EXTRA_SPECTRUM_FRAMES) ?: FloatArray(0),
                        intent.getIntExtra(
                            EXTRA_SPECTRUM_FRAME_MS,
                            SpectrumEngine.FRAME_DURATION_MS,
                        ),
                    )
                    ACTION_REQUEST_STATE -> MediaSessionStore.republishLocalSnapshot()
                }
            }
        }
        appContext.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ACTION_SNAPSHOT)
                addAction(ACTION_COMMAND)
                addAction(ACTION_SPECTRUM)
                addAction(ACTION_REQUEST_STATE)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        processApplication = application
        module.log(
            Log.INFO,
            MusicEnhanceModule.TAG,
            "Music app process bridge ready; process=${Application.getProcessName()}",
        )
        appContext.sendBroadcast(Intent(ACTION_REQUEST_STATE).setPackage(appContext.packageName))
    }

    fun publishSnapshot(snapshot: PlayerSnapshot) {
        val appContext = processApplication ?: return
        val intent = Intent(ACTION_SNAPSHOT).setPackage(appContext.packageName).apply {
            putExtra(EXTRA_TITLE, snapshot.title)
            putExtra(EXTRA_ARTIST, snapshot.artist)
            putExtra(EXTRA_ALBUM, snapshot.album)
            putExtra(EXTRA_DURATION, snapshot.durationMs)
            putExtra(EXTRA_POSITION, snapshot.positionMs)
            putExtra(EXTRA_PLAYING, snapshot.isPlaying)
            putExtra(EXTRA_ACTIONS, snapshot.actions)
            putStringArrayListExtra(EXTRA_CUSTOM_ACTIONS, ArrayList(snapshot.customActions))
            putExtra(EXTRA_REPEAT_MODE, snapshot.controls.repeatMode.name)
            snapshot.controls.favorite?.let { putExtra(EXTRA_FAVORITE, it) }
            putExtra(EXTRA_CONTROL_TITLE, snapshot.controls.songTitle)
            encodeArtwork(snapshot.artwork)?.let { putExtra(EXTRA_ARTWORK, it) }
        }
        runCatching { appContext.sendBroadcast(intent) }.onFailure {
            module.log(Log.ERROR, MusicEnhanceModule.TAG, "player snapshot bridge failed", it)
        }
    }

    fun sendCommand(command: String, positionMs: Long = 0L) {
        val appContext = processApplication ?: return
        appContext.sendBroadcast(
            Intent(ACTION_COMMAND).setPackage(appContext.packageName)
                .putExtra(EXTRA_COMMAND, command)
                .putExtra(EXTRA_POSITION, positionMs),
        )
    }

    fun publishSpectrum(frames: FloatArray, frameDurationMs: Int) {
        if (frames.isEmpty()) return
        val appContext = processApplication ?: return
        appContext.sendBroadcast(
            Intent(ACTION_SPECTRUM).setPackage(appContext.packageName)
                .putExtra(EXTRA_SPECTRUM_FRAMES, frames)
                .putExtra(EXTRA_SPECTRUM_FRAME_MS, frameDurationMs),
        )
    }

    private fun receiveSnapshot(intent: Intent) {
        val artwork = intent.getByteArrayExtra(EXTRA_ARTWORK)?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        MediaSessionStore.acceptRemoteSnapshot(
            PlayerSnapshot(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
                artwork = artwork,
                durationMs = intent.getLongExtra(EXTRA_DURATION, 0L),
                positionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
                isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false),
                actions = intent.getLongExtra(EXTRA_ACTIONS, 0L),
                customActions = intent.getStringArrayListExtra(EXTRA_CUSTOM_ACTIONS).orEmpty(),
                controls = PlayerControlState(
                    repeatMode = RepeatMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_REPEAT_MODE) } ?: RepeatMode.UNKNOWN,
                    favorite = if (intent.hasExtra(EXTRA_FAVORITE)) intent.getBooleanExtra(EXTRA_FAVORITE, false) else null,
                    songTitle = intent.getStringExtra(EXTRA_CONTROL_TITLE),
                ),
            ),
        )
    }

    private fun encodeArtwork(bitmap: Bitmap?): ByteArray? {
        bitmap ?: return null
        return runCatching {
            val largest = max(bitmap.width, bitmap.height)
            val outputBitmap = if (largest > MAX_ARTWORK_SIDE) {
                val scale = MAX_ARTWORK_SIDE.toFloat() / largest
                bitmap.scale(
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            var bytes = compressJpeg(outputBitmap, 92)
            if (bytes.size > MAX_ARTWORK_BYTES) {
                val fallbackScale = FALLBACK_ARTWORK_SIDE.toFloat() / max(outputBitmap.width, outputBitmap.height)
                val fallback = if (fallbackScale < 1f) {
                    outputBitmap.scale(
                        (outputBitmap.width * fallbackScale).toInt().coerceAtLeast(1),
                        (outputBitmap.height * fallbackScale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    outputBitmap
                }
                bytes = compressJpeg(fallback, 86)
                if (fallback !== outputBitmap) fallback.recycle()
            }
            if (outputBitmap !== bitmap) outputBitmap.recycle()
            bytes
        }.getOrNull()
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
}
