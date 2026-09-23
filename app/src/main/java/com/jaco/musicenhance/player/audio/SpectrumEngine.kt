package com.jaco.musicenhance.player.audio

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.media.PlayerProcessBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Streaming kick/bass onset detector fed by the host app's AudioTrack PCM writes. */
internal object SpectrumEngine {
    const val FRAME_DURATION_MS = 10
    private const val MAX_REMOTE_FRAMES = 800
    private var lastDebugAt = 0L
    private val detector = BassBeatDetector()
    private val remoteLock = Any()
    private var remoteFrames = FloatArray(0)
    private var remoteFrameDurationMs = FRAME_DURATION_MS
    private var remoteStartAt = 0L
    @Volatile
    private var remotePlaybackActive = true
    private val audioWriteDepth = ThreadLocal.withInitial { 0 }

    fun enterAudioWrite(): Boolean {
        val depth = audioWriteDepth.get() ?: 0
        audioWriteDepth.set(depth + 1)
        return depth == 0
    }

    fun exitAudioWrite() {
        val depth = (audioWriteDepth.get() ?: 1) - 1
        if (depth <= 0) audioWriteDepth.remove() else audioWriteDepth.set(depth)
    }

    @Synchronized
    fun capture(track: AudioTrack?, args: List<Any?>) {
        if (track?.audioAttributes?.usage?.let { it != AudioAttributes.USAGE_MEDIA } == true) return
        val pcm = when (val data = args.firstOrNull()) {
            is ByteArray -> fromBytes(data, args.intAt(1), args.intAt(2))
            is ShortArray -> fromShorts(data, args.intAt(1), args.intAt(2))
            is FloatArray -> fromFloats(data, args.intAt(1), args.intAt(2))
            is ByteBuffer -> fromBuffer(data, args.intAt(1))
            else -> null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        val frames = detector.process(
            pcm,
            track?.sampleRate?.takeIf { it > 0 } ?: 48_000,
            track?.channelCount?.takeIf { it > 0 } ?: 2,
        )
        if (frames.isNotEmpty()) PlayerProcessBridge.publishSpectrum(frames, FRAME_DURATION_MS)
        if (now - lastDebugAt >= 2_000L) {
            lastDebugAt = now
            moduleInfo("Bass beat DSP: ${detector.diagnosticsAndReset()}")
        }
    }

    fun acceptRemote(frames: FloatArray, frameDurationMs: Int) {
        if (frames.isEmpty() || !remotePlaybackActive) return
        val safeDuration = frameDurationMs.coerceIn(5, 50)
        val sanitized = FloatArray(frames.size) { frames[it].coerceIn(0f, 1f) }
        val now = SystemClock.elapsedRealtime()
        synchronized(remoteLock) {
            if (!remotePlaybackActive) return
            val remaining = if (
                remoteFrames.isNotEmpty() &&
                remoteFrameDurationMs == safeDuration &&
                remoteStartAt > 0L
            ) {
                val consumed = ((now - remoteStartAt).coerceAtLeast(0L) / safeDuration).toInt()
                if (consumed < remoteFrames.size) remoteFrames.copyOfRange(consumed, remoteFrames.size)
                else FloatArray(0)
            } else {
                FloatArray(0)
            }
            val total = (remaining.size + sanitized.size).coerceAtMost(MAX_REMOTE_FRAMES)
            remoteFrames = FloatArray(total).also { combined ->
                val retained = remaining.size.coerceAtMost(total)
                remaining.copyInto(combined, endIndex = retained)
                val appended = (total - retained).coerceAtMost(sanitized.size)
                sanitized.copyInto(combined, destinationOffset = retained, endIndex = appended)
            }
            remoteFrameDurationMs = safeDuration
            remoteStartAt = now
        }
    }

    fun bassSnapshot(): Float {
        if (!remotePlaybackActive) return 0f
        val now = SystemClock.elapsedRealtime()
        return synchronized(remoteLock) {
            if (remoteFrames.isEmpty() || remoteStartAt <= 0L) return@synchronized 0f
            val index = ((now - remoteStartAt).coerceAtLeast(0L) / remoteFrameDurationMs).toInt()
            remoteFrames.getOrElse(index) { 0f }
        }
    }

    fun clearRemoteTimeline() {
        synchronized(remoteLock) {
            remoteFrames = FloatArray(0)
            remoteStartAt = 0L
        }
    }

    fun setRemotePlaybackActive(active: Boolean) {
        remotePlaybackActive = active
        clearRemoteTimeline()
    }

    private class BassBeatDetector {
        private val bassHighPass = Biquad()
        private val bassLowPass1 = Biquad()
        private val bassLowPass2 = Biquad()
        private val punchHighPass = Biquad()
        private val punchLowPass = Biquad()
        private var configuredSampleRate = 0
        private var windowFrames = 480
        private var bassSquareSum = 0f
        private var punchSquareSum = 0f
        private var frameCount = 0
        private var initialized = false
        private var fastBassDb = -60f
        private var slowBassDb = -60f
        private var previousFastBassDb = -60f
        private var fastPunchDb = -60f
        private var slowPunchDb = -60f
        private var previousFastPunchDb = -60f
        private var pulse = 0f
        private var streamTimeMs = 0L
        private var lastBeatStreamTimeMs = Long.MIN_VALUE / 2
        private var debugMaxBassRms = 0f
        private var debugMaxPunchRms = 0f
        private var debugMaxBassSlope = 0f
        private var debugMaxBassProminence = 0f
        private var debugMaxLevel = 0f
        private var debugTriggers = 0
        private var debugFrames = 0

        fun process(input: FloatArray, sampleRate: Int, channelCount: Int): FloatArray {
            configure(sampleRate)
            val channels = channelCount.coerceIn(1, 8)
            val inputFrameCount = (input.size + channels - 1) / channels
            val output = FloatArray((frameCount + inputFrameCount) / windowFrames + 1)
            var outputCount = 0
            var index = 0
            while (index < input.size) {
                var mono = 0f
                var actualChannels = 0
                for (channel in 0 until channels) {
                    val position = index + channel
                    if (position >= input.size) break
                    mono += input[position].coerceIn(-1f, 1f)
                    actualChannels++
                }
                if (actualChannels > 0) mono /= actualChannels

                // The bass band drives most of the motion. A smaller 140-420 Hz punch band
                // supplies the drum attack so kicks remain visible on tracks with weak sub-bass.
                val bass = bassLowPass2.process(bassLowPass1.process(bassHighPass.process(mono)))
                val punch = punchLowPass.process(punchHighPass.process(mono))
                bassSquareSum += bass * bass
                punchSquareSum += punch * punch
                frameCount++
                if (frameCount >= windowFrames) output[outputCount++] = finishWindow()
                index += channels
            }
            return output.copyOf(outputCount)
        }

        private fun configure(sampleRate: Int) {
            val safeRate = sampleRate.coerceIn(8_000, 192_000)
            if (configuredSampleRate == safeRate) return
            configuredSampleRate = safeRate
            windowFrames = (safeRate / 100).coerceIn(160, 1_920)
            bassHighPass.configureHighPass(30f, safeRate.toFloat(), 0.7071f)
            bassLowPass1.configureLowPass(165f, safeRate.toFloat(), 0.7071f)
            bassLowPass2.configureLowPass(165f, safeRate.toFloat(), 0.7071f)
            punchHighPass.configureHighPass(140f, safeRate.toFloat(), 0.7071f)
            punchLowPass.configureLowPass(420f, safeRate.toFloat(), 0.7071f)
            bassSquareSum = 0f
            punchSquareSum = 0f
            frameCount = 0
            initialized = false
            pulse = 0f
            streamTimeMs = 0L
            lastBeatStreamTimeMs = Long.MIN_VALUE / 2
        }

        private fun finishWindow(): Float {
            val count = frameCount.coerceAtLeast(1)
            val bassRms = sqrt(bassSquareSum / count).coerceAtLeast(0.000001f)
            val punchRms = sqrt(punchSquareSum / count).coerceAtLeast(0.000001f)
            bassSquareSum = 0f
            punchSquareSum = 0f
            frameCount = 0
            streamTimeMs += FRAME_DURATION_MS
            debugFrames++
            val bassDb = (20.0 * log10(bassRms.toDouble())).toFloat()
            val punchDb = (20.0 * log10(punchRms.toDouble())).toFloat()

            if (!initialized) {
                fastBassDb = bassDb
                slowBassDb = bassDb
                previousFastBassDb = bassDb
                fastPunchDb = punchDb
                slowPunchDb = punchDb
                previousFastPunchDb = punchDb
                initialized = true
                return 0f
            }

            previousFastBassDb = fastBassDb
            previousFastPunchDb = fastPunchDb
            fastBassDb += (bassDb - fastBassDb) * if (bassDb > fastBassDb) 0.68f else 0.38f
            fastPunchDb += (punchDb - fastPunchDb) * if (punchDb > fastPunchDb) 0.62f else 0.40f
            val bassSlopeDb = (fastBassDb - previousFastBassDb).coerceAtLeast(0f)
            val punchSlopeDb = (fastPunchDb - previousFastPunchDb).coerceAtLeast(0f)
            val bassProminenceDb = fastBassDb - slowBassDb
            val punchProminenceDb = fastPunchDb - slowPunchDb
            pulse *= 0.86f

            val bassSlopeStrength = (bassSlopeDb / 5.5f).coerceIn(0f, 1f)
            val bassProminenceStrength = (bassProminenceDb / 11f).coerceIn(0f, 1f)
            val punchSlopeStrength = (punchSlopeDb / 7.5f).coerceIn(0f, 1f)
            val punchProminenceStrength = (punchProminenceDb / 13f).coerceIn(0f, 1f)
            val onsetStrength = (
                bassSlopeStrength * 0.38f +
                    bassProminenceStrength * 0.40f +
                    punchSlopeStrength * 0.14f +
                    punchProminenceStrength * 0.08f
                ).coerceIn(0f, 1f)

            if (
                bassRms >= 0.004f &&
                bassProminenceDb >= 0.90f &&
                (bassSlopeDb >= 0.65f || (punchSlopeDb >= 1.20f && bassProminenceDb >= 0.55f)) &&
                streamTimeMs - lastBeatStreamTimeMs >= 150L
            ) {
                pulse = max(pulse, 0.34f + onsetStrength * 0.66f)
                lastBeatStreamTimeMs = streamTimeMs
                debugTriggers++
            }

            val bassBody = bassProminenceStrength * 0.52f + bassSlopeStrength * 0.13f
            val punchBody = punchProminenceStrength * 0.08f + punchSlopeStrength * 0.05f
            val level = max(pulse, bassBody + punchBody).coerceIn(0f, 1f)

            slowBassDb += (bassDb - slowBassDb) * 0.024f
            slowPunchDb += (punchDb - slowPunchDb) * 0.030f
            debugMaxBassRms = max(debugMaxBassRms, bassRms)
            debugMaxPunchRms = max(debugMaxPunchRms, punchRms)
            debugMaxBassSlope = max(debugMaxBassSlope, bassSlopeDb)
            debugMaxBassProminence = max(debugMaxBassProminence, bassProminenceDb)
            debugMaxLevel = max(debugMaxLevel, level)
            return level
        }

        fun diagnosticsAndReset(): String {
            val message = "bass=${"%.4f".format(debugMaxBassRms)}, " +
                "punch=${"%.4f".format(debugMaxPunchRms)}, " +
                "slope=${"%.2f".format(debugMaxBassSlope)}dB, " +
                "prom=${"%.2f".format(debugMaxBassProminence)}dB, " +
                "level=${"%.2f".format(debugMaxLevel)}, triggers=$debugTriggers, frames=$debugFrames"
            debugMaxBassRms = 0f
            debugMaxPunchRms = 0f
            debugMaxBassSlope = 0f
            debugMaxBassProminence = 0f
            debugMaxLevel = 0f
            debugTriggers = 0
            debugFrames = 0
            return message
        }
    }

    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun configureLowPass(frequency: Float, sampleRate: Float, q: Float) {
            val omega = 2.0 * PI * frequency / sampleRate
            val cosine = cos(omega).toFloat()
            val alpha = (sin(omega) / (2.0 * q)).toFloat()
            val a0 = 1f + alpha
            set(
                (1f - cosine) / 2f / a0,
                (1f - cosine) / a0,
                (1f - cosine) / 2f / a0,
                (-2f * cosine) / a0,
                (1f - alpha) / a0,
            )
        }

        fun configureHighPass(frequency: Float, sampleRate: Float, q: Float) {
            val omega = 2.0 * PI * frequency / sampleRate
            val cosine = cos(omega).toFloat()
            val alpha = (sin(omega) / (2.0 * q)).toFloat()
            val a0 = 1f + alpha
            set(
                (1f + cosine) / 2f / a0,
                -(1f + cosine) / a0,
                (1f + cosine) / 2f / a0,
                (-2f * cosine) / a0,
                (1f - alpha) / a0,
            )
        }

        private fun set(nb0: Float, nb1: Float, nb2: Float, na1: Float, na2: Float) {
            b0 = nb0
            b1 = nb1
            b2 = nb2
            a1 = na1
            a2 = na2
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }

        fun process(input: Float): Float {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }
    }

    private fun fromBytes(data: ByteArray, offset: Int, requested: Int): FloatArray? {
        val start = offset.coerceIn(0, data.size)
        val end = (start + requested.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end - start < 2) return null
        return FloatArray((end - start) / 2) { index ->
            val position = start + index * 2
            val value = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            value.toShort() / 32768f
        }
    }

    private fun fromShorts(data: ShortArray, offset: Int, requested: Int): FloatArray? {
        val start = offset.coerceIn(0, data.size)
        val end = (start + requested.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end <= start) return null
        return FloatArray(end - start) { data[start + it] / 32768f }
    }

    private fun fromFloats(data: FloatArray, offset: Int, requested: Int): FloatArray? {
        val start = offset.coerceIn(0, data.size)
        val end = (start + requested.coerceAtLeast(0)).coerceAtMost(data.size)
        if (end <= start) return null
        return data.copyOfRange(start, end)
    }

    private fun fromBuffer(buffer: ByteBuffer, requestedBytes: Int): FloatArray? {
        val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val byteCount = requestedBytes.coerceAtMost(copy.remaining()).coerceAtLeast(0)
        if (byteCount < 2) return null
        return FloatArray(byteCount / 2) { copy.short / 32768f }
    }

    private fun List<Any?>.intAt(index: Int): Int = (getOrNull(index) as? Int) ?: 0
}
