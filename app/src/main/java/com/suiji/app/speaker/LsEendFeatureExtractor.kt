package com.suiji.app.speaker

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/** Streaming frontend required by the official LS-EEND 1-8 speaker checkpoint. */
class LsEendFeatureExtractor {
    private var audio = FloatArray(FFT_SIZE / 2)
    private val melFrames = ArrayDeque<FloatArray>().apply {
        repeat(CONTEXT_FRAMES) { addLast(FloatArray(MEL_BINS)) }
    }
    private val cumulativeMean = FloatArray(MEL_BINS)
    private var cumulativeCount = 0

    fun accept(samples8Khz: FloatArray): List<FloatArray> {
        if (samples8Khz.isEmpty()) return emptyList()
        audio += samples8Khz
        val features = mutableListOf<FloatArray>()
        while (audio.size >= AUDIO_WINDOW_SAMPLES) {
            extractTenMelFrames(audio.copyOfRange(0, AUDIO_WINDOW_SAMPLES))
            audio = audio.copyOfRange(AUDIO_STEP_SAMPLES, audio.size)
            while (melFrames.size >= SPLICED_MEL_FRAMES) {
                val feature = FloatArray(FEATURE_DIM)
                var offset = 0
                for (frame in melFrames.take(SPLICED_MEL_FRAMES)) {
                    frame.copyInto(feature, offset)
                    offset += MEL_BINS
                }
                features += feature
                repeat(MEL_SUBSAMPLING) { melFrames.removeFirst() }
            }
        }
        return features
    }

    private fun extractTenMelFrames(window: FloatArray) {
        repeat(MEL_SUBSAMPLING) { frameIndex ->
            val real = DoubleArray(FFT_SIZE)
            val imaginary = DoubleArray(FFT_SIZE)
            val frameStart = frameIndex * HOP_LENGTH
            repeat(WIN_LENGTH) { index ->
                real[WINDOW_OFFSET + index] =
                    window[frameStart + WINDOW_OFFSET + index].toDouble() * HANN_WINDOW[index]
            }
            fft(real, imaginary)
            val mel = FloatArray(MEL_BINS)
            repeat(MEL_BINS) { melIndex ->
                var energy = 0.0
                repeat(FFT_BINS) { bin ->
                    val power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                    energy += power * MEL_FILTERS[melIndex][bin]
                }
                mel[melIndex] = log10(energy.coerceAtLeast(1e-10)).toFloat()
            }
            cumulativeCount += 1
            repeat(MEL_BINS) { index ->
                cumulativeMean[index] +=
                    (mel[index] - cumulativeMean[index]) / cumulativeCount.toFloat()
                mel[index] -= cumulativeMean[index]
            }
            melFrames.addLast(mel)
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]
                real[i] = real[j]
                real[j] = realValue
                val imaginaryValue = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryValue
            }
        }

        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * PI / length
            val rootReal = cos(angle)
            val rootImaginary = sin(angle)
            var start = 0
            while (start < FFT_SIZE) {
                var weightReal = 1.0
                var weightImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * weightReal - imaginary[odd] * weightImaginary
                    val oddImaginary = real[odd] * weightImaginary + imaginary[odd] * weightReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextWeightReal = weightReal * rootReal - weightImaginary * rootImaginary
                    weightImaginary = weightReal * rootImaginary + weightImaginary * rootReal
                    weightReal = nextWeightReal
                }
                start += length
            }
            length = length shl 1
        }
    }

    companion object {
        const val SAMPLE_RATE = 8_000
        const val AUDIO_STEP_SAMPLES = SAMPLE_RATE / 10
        const val FEATURE_DIM = 345
        private const val WIN_LENGTH = 200
        private const val HOP_LENGTH = 80
        private const val FFT_SIZE = 1024
        private const val FFT_BINS = FFT_SIZE / 2 + 1
        private const val MEL_BINS = 23
        private const val CONTEXT_FRAMES = 7
        private const val MEL_SUBSAMPLING = 10
        private const val SPLICED_MEL_FRAMES = CONTEXT_FRAMES * 2 + 1
        private const val WINDOW_OFFSET = (FFT_SIZE - WIN_LENGTH) / 2
        private const val AUDIO_WINDOW_SAMPLES = FFT_SIZE / 2 +
            AUDIO_STEP_SAMPLES + (FFT_SIZE / 2 - HOP_LENGTH)

        private val HANN_WINDOW = DoubleArray(WIN_LENGTH) { index ->
            0.5 - 0.5 * cos(2.0 * PI * index / WIN_LENGTH)
        }

        private val MEL_FILTERS = buildMelFilters()

        private fun buildMelFilters(): Array<DoubleArray> {
            val minMel = hzToMel(0.0)
            val maxMel = hzToMel(SAMPLE_RATE / 2.0)
            val points = DoubleArray(MEL_BINS + 2) { index ->
                melToHz(minMel + (maxMel - minMel) * index / (MEL_BINS + 1))
            }
            val frequencies = DoubleArray(FFT_BINS) { it * SAMPLE_RATE.toDouble() / FFT_SIZE }
            return Array(MEL_BINS) { melIndex ->
                val lower = points[melIndex]
                val center = points[melIndex + 1]
                val upper = points[melIndex + 2]
                val normalization = 2.0 / (upper - lower)
                DoubleArray(FFT_BINS) { bin ->
                    val frequency = frequencies[bin]
                    val triangle = minOf(
                        (frequency - lower) / (center - lower),
                        (upper - frequency) / (upper - center)
                    ).coerceAtLeast(0.0)
                    triangle * normalization
                }
            }
        }

        private fun hzToMel(frequency: Double): Double {
            val linearSpacing = 200.0 / 3.0
            val logStep = ln(6.4) / 27.0
            return if (frequency < 1000.0) {
                frequency / linearSpacing
            } else {
                15.0 + ln(frequency / 1000.0) / logStep
            }
        }

        private fun melToHz(mel: Double): Double {
            val linearSpacing = 200.0 / 3.0
            val logStep = ln(6.4) / 27.0
            return if (mel < 15.0) {
                mel * linearSpacing
            } else {
                1000.0 * kotlin.math.exp(logStep * (mel - 15.0))
            }
        }
    }
}
