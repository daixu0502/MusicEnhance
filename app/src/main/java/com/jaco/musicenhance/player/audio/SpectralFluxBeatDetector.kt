package com.jaco.musicenhance.player.audio

internal data class BeatFrameBatch(
    val levels: FloatArray,
    val firstFramePosition: Long,
    val frameStep: Int,
)

/** Continuous PCM frontend; response shaping is shared with captured waveform windows. */
internal class SpectralFluxBeatDetector {
    private var sampleRate = 0
    private var fftSize = 0
    private var hopFrames = 0
    private var ring = FloatArray(0)
    private var ringWrite = 0
    private var availableFrames = 0
    private var framesSinceAnalysis = 0
    private var transform: SpectrumWindowTransform? = null
    private val envelope = SpectrumBeatEnvelope()
    private var pendingFramePosition: Long? = null

    fun reset() {
        ring.fill(0f)
        ringWrite = 0
        availableFrames = 0
        framesSinceAnalysis = 0
        envelope.reset()
        pendingFramePosition = null
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
        transform = SpectrumWindowTransform(fftSize)
        reset()
    }

    private fun analyze(framePosition: Long): Output? {
        val magnitudes = transform?.analyze { index -> ring[(ringWrite + index) % fftSize] } ?: return null
        val atNanos = framePosition / sampleRate * NANOS_PER_SECOND +
            framePosition % sampleRate * NANOS_PER_SECOND / sampleRate
        val sample = envelope.process(magnitudes, sampleRate, fftSize, atNanos)
        val candidateFramePosition = pendingFramePosition
        pendingFramePosition = framePosition
        // Keep the original integer PCM position instead of round-tripping through nanoseconds.
        return if (sample != null && candidateFramePosition != null) {
            Output(candidateFramePosition, sample.level)
        } else {
            null
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private data class Output(val framePosition: Long, val level: Float)

    private companion object {
        const val OUTPUT_RATE_HZ = 100
        const val FFT_WINDOW_SECONDS = 0.022f
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
