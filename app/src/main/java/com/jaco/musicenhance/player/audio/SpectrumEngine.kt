package com.jaco.musicenhance.player.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.media.PlayerProcessBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Detects onsets from host PCM and schedules them against AudioTrack's presentation clock. */
internal object SpectrumEngine {
    const val FRAME_DURATION_MS = 10
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-Spectrum").apply { isDaemon = true }
    }
    private val nextStreamId = AtomicLong(1)
    private val trackStates = WeakHashMap<AudioTrack, TrackState>() // Worker thread only.
    private val remoteLock = Any()
    private val remoteTimeline = SpectrumTimeline()
    @Volatile private var remotePlaybackActive = true
    private var lastDebugAtNanos = 0L
    private val audioWriteDepth = ThreadLocal.withInitial { 0 }

    internal class PendingCapture private constructor(
        val track: AudioTrack,
        val samples: FloatArray,
        val acceptedUnitsAreBytes: Boolean,
        val bytesPerSample: Int,
        val sampleRate: Int,
        val channelCount: Int,
    ) {
        companion object {
            fun samples(
                track: AudioTrack,
                samples: FloatArray,
                sampleRate: Int,
                channelCount: Int,
            ) = PendingCapture(track, samples, false, 1, sampleRate, channelCount)

            fun bytes(
                track: AudioTrack,
                samples: FloatArray,
                bytesPerSample: Int,
                sampleRate: Int,
                channelCount: Int,
            ) = PendingCapture(track, samples, true, bytesPerSample, sampleRate, channelCount)
        }
    }

    private class TrackState(
        val sampleRate: Int,
        val channelCount: Int,
        val streamId: Long,
    ) {
        val detector = SpectralFluxBeatDetector()
        val timestamp = AudioTimestamp()
        var submittedFrames = 0L
        var anchorFramePosition = 0L
        var anchorElapsedNanos = 0L
        var lastTimestampQueryNanos = 0L
        var timestampAvailable = false
    }

    fun enterAudioWrite(): Boolean {
        val depth = audioWriteDepth.get() ?: 0
        audioWriteDepth.set(depth + 1)
        return depth == 0
    }

    fun exitAudioWrite() {
        val depth = (audioWriteDepth.get() ?: 1) - 1
        if (depth <= 0) audioWriteDepth.remove() else audioWriteDepth.set(depth)
    }

    /** Copies caller-owned input before AudioTrack advances a ByteBuffer or returns to its caller. */
    fun prepareCapture(track: AudioTrack?, args: List<Any?>): PendingCapture? {
        track ?: return null
        if (track.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) return null
        val sampleRate = track.sampleRate.takeIf { it > 0 } ?: return null
        val channels = track.channelCount.takeIf { it > 0 } ?: return null
        return when (val data = args.firstOrNull()) {
            is ByteArray -> fromBytes(track, data, args.intAt(1), args.intAt(2), sampleRate, channels)
            is ShortArray -> fromShorts(track, data, args.intAt(1), args.intAt(2), sampleRate, channels)
            is FloatArray -> fromFloats(track, data, args.intAt(1), args.intAt(2), sampleRate, channels)
            is ByteBuffer -> fromBuffer(track, data, args.intAt(1), sampleRate, channels)
            else -> null
        }
    }

    /** Runs after write() so partial and failed writes cannot generate visualization frames. */
    fun commitCapture(capture: PendingCapture?, acceptedUnits: Int) {
        capture ?: return
        if (acceptedUnits <= 0) return
        val acceptedSamples = if (capture.acceptedUnitsAreBytes) {
            acceptedUnits / capture.bytesPerSample
        } else {
            acceptedUnits
        }.coerceAtMost(capture.samples.size)
        val completeSamples = acceptedSamples - acceptedSamples % capture.channelCount
        if (completeSamples <= 0) return
        worker.execute { processCapture(capture, completeSamples) }
    }

    fun reset(track: AudioTrack?) {
        track ?: return
        worker.execute {
            trackStates.remove(track)?.let { PlayerProcessBridge.clearSpectrum(it.streamId) }
        }
    }

    private fun processCapture(capture: PendingCapture, sampleCount: Int) {
        val track = capture.track
        val existing = trackStates[track]
        val state = if (
            existing == null || existing.sampleRate != capture.sampleRate ||
            existing.channelCount != capture.channelCount
        ) {
            existing?.let { PlayerProcessBridge.clearSpectrum(it.streamId) }
            TrackState(
                capture.sampleRate,
                capture.channelCount,
                createStreamId(track),
            ).also { trackStates[track] = it }
        } else {
            existing
        }
        val firstFramePosition = state.submittedFrames
        val acceptedFrames = sampleCount / state.channelCount
        state.submittedFrames += acceptedFrames
        val batch = state.detector.process(
            capture.samples,
            state.sampleRate,
            state.channelCount,
            firstFramePosition,
            sampleCount,
        ) ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        updatePresentationAnchor(track, state, nowNanos)
        val startAtNanos = state.anchorElapsedNanos +
            framesToNanos(batch.firstFramePosition - state.anchorFramePosition, state.sampleRate)
        PlayerProcessBridge.publishSpectrum(
            batch.levels,
            FRAME_DURATION_MS,
            startAtNanos,
            state.streamId,
        )
        if (nowNanos - lastDebugAtNanos >= DEBUG_INTERVAL_NANOS) {
            lastDebugAtNanos = nowNanos
            moduleInfo(
                "Spectrum clock: stream=${state.streamId}, lead=" +
                    "${(startAtNanos - nowNanos) / 1_000_000}ms, frames=${batch.levels.size}, " +
                    "timestamp=${state.timestampAvailable}",
            )
        }
    }

    private fun updatePresentationAnchor(track: AudioTrack, state: TrackState, nowElapsedNanos: Long) {
        val queryInterval = if (state.timestampAvailable) STABLE_TIMESTAMP_INTERVAL_NANOS
        else WARMUP_TIMESTAMP_INTERVAL_NANOS
        if (nowElapsedNanos - state.lastTimestampQueryNanos >= queryInterval) {
            state.lastTimestampQueryNanos = nowElapsedNanos
            val timestampFound = runCatching { track.getTimestamp(state.timestamp) }.getOrDefault(false)
            if (timestampFound) {
                // AudioTimestamp uses System.nanoTime's monotonic clock. Convert explicitly to the
                // elapsed-realtime clock shared in the cross-process packet.
                val clockOffset = nowElapsedNanos - System.nanoTime()
                state.anchorFramePosition = state.timestamp.framePosition
                state.anchorElapsedNanos = state.timestamp.nanoTime + clockOffset
                state.timestampAvailable = true
            }
        }
        if (!state.timestampAvailable) {
            state.anchorFramePosition = Integer.toUnsignedLong(track.playbackHeadPosition)
            state.anchorElapsedNanos = nowElapsedNanos
        }
    }

    fun acceptRemote(
        frames: FloatArray,
        frameDurationMs: Int,
        startAtNanos: Long,
        streamId: Long,
    ) {
        if (frames.isEmpty() || !remotePlaybackActive) return
        synchronized(remoteLock) {
            if (!remotePlaybackActive) return
            remoteTimeline.append(
                streamId,
                startAtNanos,
                frames,
                frameDurationMs,
                SystemClock.elapsedRealtimeNanos(),
            )
        }
    }

    fun clearRemoteStream(streamId: Long) {
        synchronized(remoteLock) { remoteTimeline.remove(streamId) }
    }

    fun bassSnapshot(): Float {
        if (!remotePlaybackActive) return 0f
        return synchronized(remoteLock) {
            remoteTimeline.levelAt(SystemClock.elapsedRealtimeNanos())
        }
    }

    fun clearRemoteTimeline() {
        synchronized(remoteLock) { remoteTimeline.clear() }
    }

    fun setRemotePlaybackActive(active: Boolean) {
        remotePlaybackActive = active
        clearRemoteTimeline()
    }

    private fun createStreamId(track: AudioTrack): Long =
        (track.audioSessionId.toLong() shl 32) xor nextStreamId.getAndIncrement()

    private fun framesToNanos(frames: Long, sampleRate: Int): Long =
        frames * 1_000_000_000L / sampleRate

    private fun fromBytes(
        track: AudioTrack,
        data: ByteArray,
        offset: Int,
        requestedBytes: Int,
        sampleRate: Int,
        channels: Int,
    ): PendingCapture? {
        val start = offset.coerceIn(0, data.size)
        val byteCount = requestedBytes.coerceAtLeast(0).coerceAtMost(data.size - start)
        return decodeBytes(track, ByteBuffer.wrap(data, start, byteCount).slice(), byteCount, sampleRate, channels)
    }

    private fun fromBuffer(
        track: AudioTrack,
        buffer: ByteBuffer,
        requestedBytes: Int,
        sampleRate: Int,
        channels: Int,
    ): PendingCapture? {
        val copy = buffer.duplicate()
        val byteCount = requestedBytes.coerceAtLeast(0).coerceAtMost(copy.remaining())
        copy.limit(copy.position() + byteCount)
        return decodeBytes(track, copy.slice(), byteCount, sampleRate, channels)
    }

    private fun decodeBytes(
        track: AudioTrack,
        source: ByteBuffer,
        byteCount: Int,
        sampleRate: Int,
        channels: Int,
    ): PendingCapture? {
        source.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (track.audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return null
        }
        val sampleCount = byteCount / bytesPerSample
        if (sampleCount == 0) return null
        val samples = FloatArray(sampleCount) { readSample(source, track.audioFormat) }
        return PendingCapture.bytes(track, samples, bytesPerSample, sampleRate, channels)
    }

    private fun readSample(source: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xff) - 128) / 128f
        AudioFormat.ENCODING_PCM_16BIT -> source.short / 32768f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val packed = (source.get().toInt() and 0xff) or
                ((source.get().toInt() and 0xff) shl 8) or (source.get().toInt() shl 16)
            packed / 8_388_608f
        }
        AudioFormat.ENCODING_PCM_32BIT -> source.int / 2_147_483_648f
        AudioFormat.ENCODING_PCM_FLOAT -> source.float.coerceIn(-1f, 1f)
        else -> 0f
    }

    private fun fromShorts(
        track: AudioTrack,
        data: ShortArray,
        offset: Int,
        requested: Int,
        sampleRate: Int,
        channels: Int,
    ): PendingCapture? {
        val start = offset.coerceIn(0, data.size)
        val end = (start + requested.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end <= start) return null
        return PendingCapture.samples(
            track,
            FloatArray(end - start) { data[start + it] / 32768f },
            sampleRate,
            channels,
        )
    }

    private fun fromFloats(
        track: AudioTrack,
        data: FloatArray,
        offset: Int,
        requested: Int,
        sampleRate: Int,
        channels: Int,
    ): PendingCapture? {
        val start = offset.coerceIn(0, data.size)
        val end = (start + requested.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end <= start) return null
        return PendingCapture.samples(track, data.copyOfRange(start, end), sampleRate, channels)
    }

    private fun List<Any?>.intAt(index: Int): Int = (getOrNull(index) as? Int) ?: 0

    private const val WARMUP_TIMESTAMP_INTERVAL_NANOS = 100_000_000L
    private const val STABLE_TIMESTAMP_INTERVAL_NANOS = 10_000_000_000L
    private const val DEBUG_INTERVAL_NANOS = 2_000_000_000L
}
