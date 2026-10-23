package vip.chener.musicenhance.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln1p

/** Hann-windowed FFT shared by continuous PCM and independent waveform captures. */
internal class SpectrumWindowTransform(private val fftSize: Int) {
    init {
        require(fftSize >= 2 && fftSize and (fftSize - 1) == 0)
    }

    private val window = FloatArray(fftSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))).toFloat()
    }
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2 + 1)

    /** The returned buffer is reused; consume it before analyzing another window. */
    fun analyze(readSample: (Int) -> Float): FloatArray {
        for (index in 0 until fftSize) {
            real[index] = readSample(index) * window[index]
            imaginary[index] = 0f
        }
        fft()
        for (bin in magnitudes.indices) {
            magnitudes[bin] = ln1p(hypot(real[bin], imaginary[bin]) * MAGNITUDE_SCALE / fftSize)
        }
        return magnitudes
    }

    private fun fft() {
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

    private companion object {
        const val MAGNITUDE_SCALE = 32f
    }
}
