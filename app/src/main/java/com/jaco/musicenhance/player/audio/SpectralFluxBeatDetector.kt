package com.jaco.musicenhance.player.audio

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sqrt

internal data class BeatFrameBatch(
    val levels: FloatArray,
    val firstFramePosition: Long,
    val frameStep: Int,
)

/** Low-latency onset envelope based on positive, log-magnitude spectral differences. */
internal class SpectralFluxBeatDetector {
    private var sampleRate = 0
    private var fftSize = 0
    private var hopFrames = 0
    private var ring = FloatArray(0)
    private var ringWrite = 0
    private var availableFrames = 0
    private var framesSinceAnalysis = 0
    private var window = FloatArray(0)
    private var real = FloatArray(0)
    private var imaginary = FloatArray(0)
    private var previousMagnitudes = FloatArray(0)
    private var fluxHistory = FloatArray(0)
    private var historyScratch = FloatArray(0)
    private var historyWrite = 0
    private var historyCount = 0
    private var pendingFeature: Feature? = null
    private var fluxBeforePending = 0f
    private var lastOnsetFrame = Long.MIN_VALUE / 2
    private var pulse = 0f
    private var slowBassMagnitude = 0f
    private var slowVocalMagnitude = 0f
    private var bassInitialized = false
    private var vocalInitialized = false

    fun reset() {
        ring.fill(0f)
        previousMagnitudes.fill(0f)
        fluxHistory.fill(0f)
        ringWrite = 0
        availableFrames = 0
        framesSinceAnalysis = 0
        historyWrite = 0
        historyCount = 0
        pendingFeature = null
        fluxBeforePending = 0f
        lastOnsetFrame = Long.MIN_VALUE / 2
        pulse = 0f
        slowBassMagnitude = 0f
        slowVocalMagnitude = 0f
        bassInitialized = false
        vocalInitialized = false
    }

    fun process(
        interleaved: FloatArray,
        sampleRateHz: Int,
        channelCount: Int,
        firstFramePosition: Long,
        sampleCount: Int = interleaved.size,
    ): BeatFrameBatch? {
        configure(sampleRateHz)
        val channels = channelCount.coerceIn(1, 8)
        val completeFrames = sampleCount.coerceIn(0, interleaved.size) / channels
        if (completeFrames == 0) return null
        val estimatedOutput = (framesSinceAnalysis + completeFrames) / hopFrames + 1
        val levels = FloatArray(estimatedOutput)
        var outputCount = 0
        var firstOutputPosition = 0L

        for (frameIndex in 0 until completeFrames) {
            val sampleOffset = frameIndex * channels
            var mono = 0f
            for (channel in 0 until channels) mono += interleaved[sampleOffset + channel]
            ring[ringWrite] = (mono / channels).coerceIn(-1f, 1f)
            ringWrite = (ringWrite + 1) % fftSize
            availableFrames = (availableFrames + 1).coerceAtMost(fftSize)
            framesSinceAnalysis++
            if (availableFrames == fftSize && framesSinceAnalysis >= hopFrames) {
                framesSinceAnalysis = 0
                val position = firstFramePosition + frameIndex
                analyze(position)?.let { output ->
                    if (outputCount == 0) firstOutputPosition = output.framePosition
                    levels[outputCount++] = output.level
                }
            }
        }
        return if (outputCount == 0) null else BeatFrameBatch(
            levels.copyOf(outputCount),
            firstOutputPosition,
            hopFrames,
        )
    }

    private fun configure(requestedSampleRate: Int) {
        val safeRate = requestedSampleRate.coerceIn(8_000, 192_000)
        if (sampleRate == safeRate) return
        sampleRate = safeRate
        fftSize = nextPowerOfTwo((safeRate * FFT_WINDOW_SECONDS).toInt()).coerceIn(256, 4096)
        hopFrames = (safeRate / OUTPUT_RATE_HZ).coerceAtLeast(1)
        ring = FloatArray(fftSize)
        window = FloatArray(fftSize) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))).toFloat()
        }
        real = FloatArray(fftSize)
        imaginary = FloatArray(fftSize)
        previousMagnitudes = FloatArray(fftSize / 2 + 1)
        fluxHistory = FloatArray(OUTPUT_RATE_HZ)
        historyScratch = FloatArray(fluxHistory.size)
        reset()
    }

    private fun analyze(framePosition: Long): Output? {
        for (index in 0 until fftSize) {
            real[index] = ring[(ringWrite + index) % fftSize] * window[index]
            imaginary[index] = 0f
        }
        fft(real, imaginary)

        var weightedFlux = 0f
        var fluxWeight = 0f
        var bassMagnitude = 0f
        var bassBins = 0
        var vocalMagnitudeSquareSum = 0f
        var vocalBins = 0
        for (bin in 1..fftSize / 2) {
            val frequency = bin.toFloat() * sampleRate / fftSize
            if (frequency > ATTACK_HIGH_HZ) break
            val magnitude = ln1p(hypot(real[bin], imaginary[bin]) * MAGNITUDE_SCALE / fftSize)
            val positiveDifference = (magnitude - previousMagnitudes[bin]).coerceAtLeast(0f)
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
        val vocalMagnitude = if (vocalBins > 0) {
            sqrt(vocalMagnitudeSquareSum / vocalBins)
        } else {
            0f
        }
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
        slowBassMagnitude += (averageBass - slowBassMagnitude) * SLOW_BASS_ALPHA
        slowVocalMagnitude += (vocalMagnitude - slowVocalMagnitude) * SLOW_VOCAL_ALPHA
        val bassBody = (bassProminence / BASS_PROMINENCE_RANGE).coerceIn(0f, 1f) * MAX_BASS_BODY_LEVEL
        val vocalBody = (vocalProminence / VOCAL_PROMINENCE_RANGE).coerceIn(0f, 1f) * MAX_VOCAL_BODY_LEVEL
        val body = (bassBody + vocalBody).coerceAtMost(MAX_COMBINED_BODY_LEVEL)

        val median = historyMedian()
        val mad = historyMad(median)
        val threshold = median + max(MINIMUM_FLUX_THRESHOLD, mad * MAD_MULTIPLIER)
        addFluxHistory(flux)
        val current = Feature(framePosition, flux, threshold, mad, body)
        val candidate = pendingFeature
        if (candidate == null) {
            pendingFeature = current
            return null
        }

        val warmedUp = historyCount >= MINIMUM_HISTORY_FRAMES
        val minimumSpacingFrames = sampleRate * MINIMUM_ONSET_INTERVAL_MS / 1_000L
        val isPeak = warmedUp && candidate.flux > candidate.threshold &&
            candidate.flux >= fluxBeforePending && candidate.flux > current.flux &&
            candidate.framePosition - lastOnsetFrame >= minimumSpacingFrames
        if (isPeak) {
            val scale = max(candidate.mad * 6f, max(candidate.threshold * 0.45f, 0.0035f))
            val strength = ((candidate.flux - candidate.threshold) / scale).coerceIn(0f, 1f)
            pulse = max(pulse, MINIMUM_PULSE_LEVEL + strength * (1f - MINIMUM_PULSE_LEVEL))
            lastOnsetFrame = candidate.framePosition
        } else {
            pulse *= PULSE_RELEASE_PER_FRAME
        }
        val output = Output(candidate.framePosition, max(pulse, candidate.body).coerceIn(0f, 1f))
        fluxBeforePending = candidate.flux
        pendingFeature = current
        return output
    }

    private fun historyMedian(): Float {
        if (historyCount == 0) return 0f
        copyHistoryToScratch { it }
        Arrays.sort(historyScratch, 0, historyCount)
        return historyScratch[historyCount / 2]
    }

    private fun historyMad(median: Float): Float {
        if (historyCount == 0) return 0f
        copyHistoryToScratch { kotlin.math.abs(it - median) }
        Arrays.sort(historyScratch, 0, historyCount)
        return historyScratch[historyCount / 2]
    }

    private inline fun copyHistoryToScratch(transform: (Float) -> Float) {
        for (index in 0 until historyCount) historyScratch[index] = transform(fluxHistory[index])
    }

    private fun addFluxHistory(value: Float) {
        fluxHistory[historyWrite] = value
        historyWrite = (historyWrite + 1) % fluxHistory.size
        historyCount = (historyCount + 1).coerceAtMost(fluxHistory.size)
    }

    private fun fft(real: FloatArray, imaginary: FloatArray) {
        var target = 0
        for (index in 1 until fftSize) {
            var bit = fftSize shr 1
            while (target and bit != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target xor bit
            if (index < target) {
                val realValue = real[index]
                real[index] = real[target]
                real[target] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[target]
                imaginary[target] = imaginaryValue
            }
        }
        var length = 2
        while (length <= fftSize) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = kotlin.math.sin(angle).toFloat()
            var offset = 0
            while (offset < fftSize) {
                var twiddleReal = 1f
                var twiddleImaginary = 0f
                for (index in 0 until length / 2) {
                    val even = offset + index
                    val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                offset += length
            }
            length = length shl 1
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private data class Feature(
        val framePosition: Long,
        val flux: Float,
        val threshold: Float,
        val mad: Float,
        val body: Float,
    )

    private data class Output(val framePosition: Long, val level: Float)

    private companion object {
        const val OUTPUT_RATE_HZ = 100
        const val FFT_WINDOW_SECONDS = 0.022f
        const val BASS_LOW_HZ = 35f
        const val BASS_CORE_HIGH_HZ = 180f
        const val BASS_HIGH_HZ = 500f
        const val VOCAL_HIGH_HZ = 4_000f
        const val ATTACK_HIGH_HZ = 8_000f
        const val MAGNITUDE_SCALE = 32f
        const val SLOW_BASS_ALPHA = 0.018f
        const val SLOW_VOCAL_ALPHA = 0.024f
        const val BASS_PROMINENCE_RANGE = 0.22f
        const val VOCAL_PROMINENCE_RANGE = 0.10f
        const val MAX_BASS_BODY_LEVEL = 0.28f
        const val MAX_VOCAL_BODY_LEVEL = 0.20f
        const val MAX_COMBINED_BODY_LEVEL = 0.38f
        const val MAD_MULTIPLIER = 2.2f
        const val MINIMUM_FLUX_THRESHOLD = 0.00135f
        const val MINIMUM_HISTORY_FRAMES = 12
        const val MINIMUM_ONSET_INTERVAL_MS = 90L
        const val MINIMUM_PULSE_LEVEL = 0.42f
        const val PULSE_RELEASE_PER_FRAME = 0.84f
    }
}
