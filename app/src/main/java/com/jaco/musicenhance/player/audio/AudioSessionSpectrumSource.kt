package com.jaco.musicenhance.player.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp

internal fun interface SessionSpectrumCapture {
    fun release()
}

/** Captures one host-owned output session; Android's recording permission is required by Visualizer. */
internal class AudioSessionSpectrumSource(
    context: Context,
    workerHandler: Handler? = null,
    private val hasCapturePermission: () -> Boolean = {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
    private val createCapture: (Int, (ByteArray, Int, Long) -> Unit) -> SessionSpectrumCapture =
        ::createVisualizerCapture,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val log: (String) -> Unit = ::moduleInfo,
) {
    private val thread = if (workerHandler == null) HandlerThread("MusicEnhance-session-spectrum").apply { start() } else null
    private val worker = workerHandler ?: Handler(requireNotNull(thread).looper)
    private val generation = AtomicLong()
    private val pending = AtomicReference<Packet?>()
    private val analysisScheduled = AtomicBoolean()
    private val detector = CapturedSpectrumBeatDetector()
    @Volatile private var sessionId = 0
    @Volatile private var playbackActive = false
    @Volatile private var released = false
    @Volatile private var level = Level()
    private var capture: SessionSpectrumCapture? = null // Worker thread only.

    private data class Packet(val generation: Long, val fft: ByteArray, val sampleRateHz: Int, val atNanos: Long)
    private data class Level(val value: Float = 0f, val atNanos: Long = 0L)

    fun setAudioSession(sessionId: Int) {
        if (released) return
        val next = sessionId.coerceAtLeast(0)
        if (this.sessionId == next) return
        this.sessionId = next
        scheduleCapture()
    }

    fun setPlaybackActive(active: Boolean) {
        if (released || playbackActive == active) return
        playbackActive = active
        scheduleCapture()
    }

    private fun scheduleCapture() {
        val request = generation.incrementAndGet()
        level = Level()
        worker.post { reconcileCapture(request) }
    }

    private fun reconcileCapture(request: Long) {
        if (request != generation.get()) return
        stopCapture()
        if (released || !playbackActive || sessionId <= 0) return
        try {
            if (!hasCapturePermission()) {
                log("Audio session spectrum unavailable: host RECORD_AUDIO permission not granted")
                return
            }
            val next = createCapture(sessionId) { fft, sampleRateHz, atNanos ->
                if (released || request != generation.get()) return@createCapture
                // Native callback buffers are reused. Retain at most one owned, bounded snapshot.
                if (fft.size !in 128..MAX_FFT_BYTES || sampleRateHz !in 8_000..192_000) return@createCapture
                pending.set(Packet(request, fft.copyOf(), sampleRateHz, atNanos))
                scheduleAnalysis()
            }
            if (released || request != generation.get()) next.release() else {
                capture = next
                log("Audio session spectrum ready: session=$sessionId")
            }
        } catch (error: Throwable) {
            log("Audio session spectrum failed for session=$sessionId: $error")
        }
    }

    private fun scheduleAnalysis() {
        if (analysisScheduled.compareAndSet(false, true)) worker.post(analyze)
    }

    private val analyze = object : Runnable {
        override fun run() {
            val packet = pending.getAndSet(null)
            if (packet != null && !released && packet.generation == generation.get()) {
                val now = nowNanos()
                if (packet.atNanos <= now && now - packet.atNanos <= MAX_CAPTURE_AGE_NANOS) {
                    val value = detector.process(packet.fft, packet.sampleRateHz, packet.atNanos)
                    if (!released && packet.generation == generation.get()) level = Level(value, packet.atNanos)
                }
            }
            analysisScheduled.set(false)
            if (pending.get() != null && !released) scheduleAnalysis()
        }
    }

    /** A stalled or lost output callback decays to silence without holding the last peak. */
    fun bassSnapshot(): Float {
        if (released || !playbackActive || sessionId <= 0) return 0f
        val current = level
        val ageNanos = (nowNanos() - current.atNanos).coerceAtLeast(0L)
        if (ageNanos >= MAX_CAPTURE_AGE_NANOS) return 0f
        val releaseMs = ((ageNanos - HOLD_NANOS).coerceAtLeast(0L) / 1_000_000f)
        return current.value * exp(-releaseMs / 120f)
    }

    fun release() {
        if (released) return
        released = true
        generation.incrementAndGet()
        level = Level()
        pending.set(null)
        worker.post {
            stopCapture()
            thread?.quitSafely()
        }
    }

    private fun stopCapture() {
        capture?.let { runCatching(it::release).onFailure { error -> log("Audio spectrum release failed: $error") } }
        capture = null
        pending.set(null)
        worker.removeCallbacks(analyze)
        analysisScheduled.set(false)
        detector.reset()
    }

    private companion object {
        const val MAX_FFT_BYTES = 1_024
        const val HOLD_NANOS = 60_000_000L
        const val MAX_CAPTURE_AGE_NANOS = 300_000_000L
    }
}

@SuppressLint("MissingPermission") // Runs in the host, after checking that host's actual recording grant.
private fun createVisualizerCapture(sessionId: Int, onFft: (ByteArray, Int, Long) -> Unit): SessionSpectrumCapture {
    require(sessionId > 0) // Session zero would capture the global mix instead of this host's output.
    val visualizer = Visualizer(sessionId)
    try {
        val range = Visualizer.getCaptureSizeRange()
        val captureSize = Integer.highestOneBit(range[1].coerceAtMost(1_024))
        require(captureSize >= range[0])
        check(visualizer.setCaptureSize(captureSize) == Visualizer.SUCCESS)
        // Normalized capture amplifies quiet passages and can leave the display almost full.
        check(visualizer.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED) == Visualizer.SUCCESS)
        val listener = object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(effect: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
            override fun onFftDataCapture(effect: Visualizer, fft: ByteArray, samplingRate: Int) {
                onFft(fft, samplingRate / 1_000, SystemClock.elapsedRealtimeNanos())
            }
        }
        check(visualizer.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate().coerceAtMost(50_000), false, true) == Visualizer.SUCCESS)
        check(visualizer.setEnabled(true) == Visualizer.SUCCESS)
        return SessionSpectrumCapture { visualizer.release() }
    } catch (error: Throwable) {
        runCatching(visualizer::release)
        throw error
    }
}
