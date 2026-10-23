package vip.chener.musicenhance.player.audio

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Shared spectral-flux onset detection and release shaping, independent of capture cadence. */
internal class SpectrumBeatEnvelope {
    data class Sample(val atNanos: Long, val pulse: Float, val body: Float) {
        val level: Float get() = max(pulse, body).coerceIn(0f, 1f)

        fun levelAt(nowNanos: Long): Float {
            val elapsedNanos = (nowNanos - atNanos).coerceAtLeast(0L)
            return max(pulse * releaseFor(elapsedNanos), body).coerceIn(0f, 1f)
        }
    }

    private var sampleRateHz = 0
    private var fftSize = 0
    private var previousMagnitudes = FloatArray(0)
    private val fluxHistory = FloatArray(HISTORY_CAPACITY)
    private val historyTimesNanos = LongArray(HISTORY_CAPACITY)
    private val historyScratch = FloatArray(HISTORY_CAPACITY)
    private var historyWrite = 0
    private var historyCount = 0
    private var pendingFeature: Feature? = null
    private var fluxBeforePending = 0f
    private var lastAcceptedAtNanos: Long? = null
    private var lastOutputAtNanos: Long? = null
    private var lastOnsetAtNanos: Long? = null
    private var pulse = 0f
    private var slowBassMagnitude = 0f
    private var slowVocalMagnitude = 0f
    private var bassInitialized = false
    private var vocalInitialized = false

    fun reset() {
        previousMagnitudes.fill(0f)
        historyWrite = 0
        historyCount = 0
        pendingFeature = null
        fluxBeforePending = 0f
        lastAcceptedAtNanos = null
        lastOutputAtNanos = null
        lastOnsetAtNanos = null
        pulse = 0f
        slowBassMagnitude = 0f
        slowVocalMagnitude = 0f
        bassInitialized = false
        vocalInitialized = false
    }

    /** A baseline window primes capture history without treating startup energy as an onset. */
    fun process(
        magnitudes: FloatArray,
        sampleRateHz: Int,
        fftSize: Int,
        atNanos: Long,
        baselineOnly: Boolean = false,
    ): Sample? {
        if (sampleRateHz <= 0 || fftSize < 2 || fftSize and (fftSize - 1) != 0 ||
            magnitudes.size != fftSize / 2 + 1 || magnitudes.any { !it.isFinite() || it < 0f }
        ) return null
        // Reject stale captures before changing format or priming any baseline.
        val previousAtNanos = lastAcceptedAtNanos
        if (previousAtNanos != null && atNanos <= previousAtNanos) return null
        if (this.sampleRateHz != sampleRateHz || this.fftSize != fftSize) {
            this.sampleRateHz = sampleRateHz
            this.fftSize = fftSize
            previousMagnitudes = FloatArray(magnitudes.size)
            reset()
        } else if (baselineOnly) {
            reset()
        }
        val elapsedNanos = if (baselineOnly) NOMINAL_INTERVAL_NANOS else {
            previousAtNanos?.let { atNanos - it } ?: NOMINAL_INTERVAL_NANOS
        }

        var weightedFlux = 0f
        var fluxWeight = 0f
        var bassMagnitude = 0f
        var bassBins = 0
        var vocalMagnitudeSquareSum = 0f
        var vocalBins = 0
        for (bin in 1..fftSize / 2) {
            val frequency = bin.toFloat() * sampleRateHz / fftSize
            if (frequency > ATTACK_HIGH_HZ) break
            val magnitude = magnitudes[bin]
            val positiveDifference = if (baselineOnly) 0f else {
                (magnitude - previousMagnitudes[bin]).coerceAtLeast(0f)
            }
            previousMagnitudes[bin] = magnitude
            val weight = when {
                frequency < BASS_LOW_HZ -> 0f
                frequency <= BASS_CORE_HIGH_HZ -> 1f
                frequency <= BASS_HIGH_HZ -> 0.62f
                frequency <= VOCAL_HIGH_HZ -> 0.18f
                else -> 0.10f
            }
            weightedFlux += positiveDifference * weight
            fluxWeight += weight
            if (frequency in BASS_LOW_HZ..BASS_HIGH_HZ) {
                bassMagnitude += magnitude
                bassBins++
            }
            if (frequency > BASS_HIGH_HZ && frequency <= VOCAL_HIGH_HZ) {
                vocalMagnitudeSquareSum += magnitude * magnitude
                vocalBins++
            }
        }
        val flux = if (fluxWeight > 0f) weightedFlux / fluxWeight else 0f
        val averageBass = if (bassBins > 0) bassMagnitude / bassBins else 0f
        val vocalMagnitude = if (vocalBins > 0) sqrt(vocalMagnitudeSquareSum / vocalBins) else 0f
        if (!bassInitialized) {
            slowBassMagnitude = averageBass
            bassInitialized = true
        }
        if (!vocalInitialized) {
            slowVocalMagnitude = vocalMagnitude
            vocalInitialized = true
        }
        val bassProminence = (averageBass - slowBassMagnitude).coerceAtLeast(0f)
        val vocalProminence = (vocalMagnitude - slowVocalMagnitude).coerceAtLeast(0f)
        slowBassMagnitude += (averageBass - slowBassMagnitude) * alphaFor(SLOW_BASS_ALPHA, elapsedNanos)
        slowVocalMagnitude += (vocalMagnitude - slowVocalMagnitude) * alphaFor(SLOW_VOCAL_ALPHA, elapsedNanos)
        val bassBody = (bassProminence / BASS_PROMINENCE_RANGE).coerceIn(0f, 1f) * MAX_BASS_BODY_LEVEL
        val vocalBody = (vocalProminence / VOCAL_PROMINENCE_RANGE).coerceIn(0f, 1f) * MAX_VOCAL_BODY_LEVEL
        val body = (bassBody + vocalBody).coerceAtMost(MAX_COMBINED_BODY_LEVEL)

        expireHistory(atNanos)
        val median = historyMedian()
        val mad = historyMad(median)
        val threshold = median + max(MINIMUM_FLUX_THRESHOLD, mad * MAD_MULTIPLIER)
        addFluxHistory(flux, atNanos)
        val current = Feature(atNanos, flux, threshold, mad, body)
        lastAcceptedAtNanos = atNanos
        val candidate = pendingFeature
        if (candidate == null) {
            pendingFeature = current
            return null
        }

        val oldestAtNanos = historyTimesNanos[oldestHistoryIndex()]
        val warmedUp = historyCount >= 2 && atNanos - oldestAtNanos >= WARMUP_NANOS
        val spaced = lastOnsetAtNanos?.let { candidate.atNanos - it >= MINIMUM_ONSET_INTERVAL_NANOS } ?: true
        val isPeak = warmedUp && candidate.flux > candidate.threshold &&
            candidate.flux >= fluxBeforePending && candidate.flux > current.flux && spaced
        if (isPeak) {
            val scale = max(candidate.mad * 6f, max(candidate.threshold * 0.45f, 0.0035f))
            val strength = ((candidate.flux - candidate.threshold) / scale).coerceIn(0f, 1f)
            pulse = max(pulse, MINIMUM_PULSE_LEVEL + strength * (1f - MINIMUM_PULSE_LEVEL))
            lastOnsetAtNanos = candidate.atNanos
        } else {
            val outputIntervalNanos = lastOutputAtNanos?.let { candidate.atNanos - it }
                ?: (current.atNanos - candidate.atNanos)
            pulse *= releaseFor(outputIntervalNanos)
        }
        val output = Sample(candidate.atNanos, pulse, candidate.body)
        lastOutputAtNanos = candidate.atNanos
        fluxBeforePending = candidate.flux
        pendingFeature = current
        return output
    }

    private fun expireHistory(atNanos: Long) {
        while (historyCount > 0 && atNanos - historyTimesNanos[oldestHistoryIndex()] > HISTORY_NANOS) {
            historyCount--
        }
    }

    private fun oldestHistoryIndex(): Int =
        (historyWrite - historyCount + HISTORY_CAPACITY) % HISTORY_CAPACITY

    private fun historyMedian(): Float {
        if (historyCount == 0) return 0f
        copyHistoryToScratch { it }
        Arrays.sort(historyScratch, 0, historyCount)
        return historyScratch[historyCount / 2]
    }

    private fun historyMad(median: Float): Float {
        if (historyCount == 0) return 0f
        copyHistoryToScratch { abs(it - median) }
        Arrays.sort(historyScratch, 0, historyCount)
        return historyScratch[historyCount / 2]
    }

    private inline fun copyHistoryToScratch(transform: (Float) -> Float) {
        val oldest = oldestHistoryIndex()
        for (index in 0 until historyCount) {
            historyScratch[index] = transform(fluxHistory[(oldest + index) % HISTORY_CAPACITY])
        }
    }

    private fun addFluxHistory(value: Float, atNanos: Long) {
        fluxHistory[historyWrite] = value
        historyTimesNanos[historyWrite] = atNanos
        historyWrite = (historyWrite + 1) % HISTORY_CAPACITY
        historyCount = (historyCount + 1).coerceAtMost(HISTORY_CAPACITY)
    }

    private data class Feature(
        val atNanos: Long,
        val flux: Float,
        val threshold: Float,
        val mad: Float,
        val body: Float,
    )

    private companion object {
        const val HISTORY_CAPACITY = 100
        const val HISTORY_NANOS = 1_000_000_000L
        const val WARMUP_NANOS = 110_000_000L
        const val NOMINAL_INTERVAL_NANOS = 10_000_000L
        const val NOMINAL_INTERVAL_TOLERANCE_NANOS = 1_000L
        const val BASS_LOW_HZ = 35f
        const val BASS_CORE_HIGH_HZ = 180f
        const val BASS_HIGH_HZ = 500f
        const val VOCAL_HIGH_HZ = 4_000f
        const val ATTACK_HIGH_HZ = 8_000f
        const val SLOW_BASS_ALPHA = 0.018f
        const val SLOW_VOCAL_ALPHA = 0.024f
        const val BASS_PROMINENCE_RANGE = 0.22f
        const val VOCAL_PROMINENCE_RANGE = 0.10f
        const val MAX_BASS_BODY_LEVEL = 0.28f
        const val MAX_VOCAL_BODY_LEVEL = 0.20f
        const val MAX_COMBINED_BODY_LEVEL = 0.38f
        const val MAD_MULTIPLIER = 2.2f
        const val MINIMUM_FLUX_THRESHOLD = 0.00135f
        const val MINIMUM_ONSET_INTERVAL_NANOS = 90_000_000L
        const val MINIMUM_PULSE_LEVEL = 0.42f
        const val PULSE_RELEASE_PER_FRAME = 0.84f

        fun releaseFor(elapsedNanos: Long): Float = when {
            elapsedNanos <= 0L -> 1f
            abs(elapsedNanos - NOMINAL_INTERVAL_NANOS) <= NOMINAL_INTERVAL_TOLERANCE_NANOS -> PULSE_RELEASE_PER_FRAME
            else -> PULSE_RELEASE_PER_FRAME.toDouble().pow(elapsedNanos.toDouble() / NOMINAL_INTERVAL_NANOS).toFloat()
        }

        fun alphaFor(nominalAlpha: Float, elapsedNanos: Long): Float {
            if (abs(elapsedNanos - NOMINAL_INTERVAL_NANOS) <= NOMINAL_INTERVAL_TOLERANCE_NANOS) return nominalAlpha
            return (1.0 - (1.0 - nominalAlpha).pow(elapsedNanos.toDouble() / NOMINAL_INTERVAL_NANOS)).toFloat()
        }
    }
}
