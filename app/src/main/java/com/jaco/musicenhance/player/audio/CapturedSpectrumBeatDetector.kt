package com.jaco.musicenhance.player.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.max

/**
 * Detects bass attacks in independent FFT captures. Use from one audio worker: captures may
 * overlap or omit samples, so their timestamps, rather than an accumulated PCM frame count,
 * determine the release and adaptive noise floor.
 */
internal class CapturedSpectrumBeatDetector {
    private var sampleRateHz = 0
    private var fftSize = 0
    private var previousMagnitudes = DoubleArray(0)
    private var lastCaptureAtNanos = NO_CAPTURE
    private var lastOnsetAtNanos = NO_CAPTURE
    private var fluxMean = 0.0
    private var fluxDeviation = 0.0
    private var pulse = 0f

    /** Visualizer FFT layout: signed DC, Nyquist, then signed real/imaginary pairs. */
    fun process(fft: ByteArray, sampleRateHz: Int, capturedAtNanos: Long): Float {
        // A late callback must neither rewind the release clock nor replace the current baseline.
        if (capturedAtNanos < 0 ||
            lastCaptureAtNanos != NO_CAPTURE && capturedAtNanos <= lastCaptureAtNanos
        ) return pulse

        val elapsedNanos = if (lastCaptureAtNanos == NO_CAPTURE) 0L
        else capturedAtNanos - lastCaptureAtNanos
        if (sampleRateHz !in MINIMUM_SAMPLE_RATE_HZ..MAXIMUM_SAMPLE_RATE_HZ ||
            fft.size !in MINIMUM_FFT_SIZE..MAXIMUM_FFT_SIZE ||
            (fft.size and (fft.size - 1)) != 0
        ) {
            reset()
            lastCaptureAtNanos = capturedAtNanos
            return 0f
        }

        val baselineOnly = this.sampleRateHz != sampleRateHz || fftSize != fft.size ||
            elapsedNanos >= MAX_CAPTURE_GAP_NANOS
        if (baselineOnly) {
            this.sampleRateHz = sampleRateHz
            fftSize = fft.size
            previousMagnitudes = DoubleArray(fft.size / 2)
            resetSignalHistory()
        }
        lastCaptureAtNanos = capturedAtNanos

        var positiveFlux = 0.0
        var totalWeight = 0.0
        // DC and Nyquist are not bass bins. Higher frequencies never contribute to an onset.
        for (bin in 1 until fft.size / 2) {
            val frequencyHz = bin.toDouble() * sampleRateHz / fft.size
            if (frequencyHz > BASS_HIGH_HZ) break
            if (frequencyHz < BASS_LOW_HZ) continue
            val magnitude = ln1p(hypot(fft[bin * 2].toDouble(), fft[bin * 2 + 1].toDouble()) /
                MAGNITUDE_SCALE)
            val weight = if (frequencyHz <= BASS_CORE_HIGH_HZ) 1.0 else UPPER_BASS_WEIGHT
            if (!baselineOnly) {
                positiveFlux += max(0.0, magnitude - previousMagnitudes[bin]) * weight
            }
            previousMagnitudes[bin] = magnitude
            totalWeight += weight
        }
        if (baselineOnly) return 0f

        pulse *= exp(-elapsedNanos.toDouble() / PULSE_RELEASE_NANOS).toFloat()
        val flux = if (totalWeight > 0.0) positiveFlux / totalWeight else 0.0
        val threshold = max(MINIMUM_FLUX_THRESHOLD, fluxMean + fluxDeviation * DEVIATION_MULTIPLIER)
        if (flux > threshold &&
            (lastOnsetAtNanos == NO_CAPTURE ||
                capturedAtNanos - lastOnsetAtNanos >= MINIMUM_ONSET_INTERVAL_NANOS)
        ) {
            val strength = (1.0 - exp(-(flux - threshold) / ATTACK_RANGE)).toFloat()
            pulse = max(pulse, MINIMUM_PULSE_LEVEL + strength * (1f - MINIMUM_PULSE_LEVEL))
            lastOnsetAtNanos = capturedAtNanos
        }

        // Time-based smoothing keeps the same history duration when capture cadence changes.
        val historyAlpha = 1.0 - exp(-elapsedNanos.toDouble() / HISTORY_NANOS)
        fluxDeviation += (abs(flux - fluxMean) - fluxDeviation) * historyAlpha
        fluxMean += (flux - fluxMean) * historyAlpha
        return pulse.coerceIn(0f, 1f)
    }

    fun reset() {
        sampleRateHz = 0
        fftSize = 0
        previousMagnitudes = DoubleArray(0)
        lastCaptureAtNanos = NO_CAPTURE
        resetSignalHistory()
    }

    private fun resetSignalHistory() {
        lastOnsetAtNanos = NO_CAPTURE
        fluxMean = 0.0
        fluxDeviation = 0.0
        pulse = 0f
    }

    private companion object {
        const val NO_CAPTURE = Long.MIN_VALUE
        const val MINIMUM_SAMPLE_RATE_HZ = 8_000
        const val MAXIMUM_SAMPLE_RATE_HZ = 192_000
        const val MINIMUM_FFT_SIZE = 8
        const val MAXIMUM_FFT_SIZE = 8_192
        const val BASS_LOW_HZ = 35.0
        const val BASS_CORE_HIGH_HZ = 180.0
        const val BASS_HIGH_HZ = 500.0
        const val UPPER_BASS_WEIGHT = 0.45
        const val MAGNITUDE_SCALE = 16.0
        const val MINIMUM_FLUX_THRESHOLD = 0.022
        const val DEVIATION_MULTIPLIER = 2.4
        const val ATTACK_RANGE = 0.18
        const val MINIMUM_PULSE_LEVEL = 0.35f
        const val MINIMUM_ONSET_INTERVAL_NANOS = 90_000_000L
        const val MAX_CAPTURE_GAP_NANOS = 600_000_000L
        const val PULSE_RELEASE_NANOS = 120_000_000.0
        const val HISTORY_NANOS = 700_000_000.0
    }
}
