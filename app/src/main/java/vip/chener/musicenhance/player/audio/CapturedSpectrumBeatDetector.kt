package vip.chener.musicenhance.player.audio

/**
 * Adapts independent Visualizer waveform windows to the same analysis used for host PCM.
 * Windows may overlap or omit audio, so they must not be stitched into a continuous stream.
 * Call only from the audio worker.
 */
internal class CapturedSpectrumBeatDetector {
    private val envelope = SpectrumBeatEnvelope()
    private var transform: SpectrumWindowTransform? = null
    private var sampleRateHz = 0
    private var windowSize = 0
    private var lastCaptureAtNanos = NO_CAPTURE
    private var sample: SpectrumBeatEnvelope.Sample? = null

    /** Visualizer waveform samples are unsigned 8-bit PCM, with silence at 128. */
    fun process(waveform: ByteArray, sampleRateHz: Int, capturedAtNanos: Long): SpectrumBeatEnvelope.Sample? {
        if (capturedAtNanos < 0 ||
            lastCaptureAtNanos != NO_CAPTURE && capturedAtNanos <= lastCaptureAtNanos
        ) return sample
        if (sampleRateHz !in MINIMUM_SAMPLE_RATE_HZ..MAXIMUM_SAMPLE_RATE_HZ ||
            waveform.size !in MINIMUM_WINDOW_SIZE..MAXIMUM_WINDOW_SIZE ||
            (waveform.size and (waveform.size - 1)) != 0
        ) {
            reset()
            lastCaptureAtNanos = capturedAtNanos
            return null
        }

        val baselineOnly = this.sampleRateHz != sampleRateHz || windowSize != waveform.size ||
            lastCaptureAtNanos != NO_CAPTURE && capturedAtNanos - lastCaptureAtNanos >= MAX_CAPTURE_GAP_NANOS
        if (baselineOnly) {
            this.sampleRateHz = sampleRateHz
            windowSize = waveform.size
            transform = SpectrumWindowTransform(windowSize)
            envelope.reset()
            sample = null
        }
        lastCaptureAtNanos = capturedAtNanos
        val magnitudes = requireNotNull(transform).analyze { index ->
            ((waveform[index].toInt() and 0xff) - 128) / 128f
        }
        if (baselineOnly) {
            // Opening a playing session, changing format or resuming after a gap is not an onset.
            envelope.process(magnitudes, sampleRateHz, windowSize, capturedAtNanos, baselineOnly = true)
        } else {
            sample = envelope.process(magnitudes, sampleRateHz, windowSize, capturedAtNanos)
        }
        return sample
    }

    fun reset() {
        envelope.reset()
        transform = null
        sampleRateHz = 0
        windowSize = 0
        lastCaptureAtNanos = NO_CAPTURE
        sample = null
    }

    private companion object {
        const val NO_CAPTURE = Long.MIN_VALUE
        const val MINIMUM_SAMPLE_RATE_HZ = 8_000
        const val MAXIMUM_SAMPLE_RATE_HZ = 192_000
        const val MINIMUM_WINDOW_SIZE = 128
        const val MAXIMUM_WINDOW_SIZE = 8_192
        const val MAX_CAPTURE_GAP_NANOS = 600_000_000L
    }
}
