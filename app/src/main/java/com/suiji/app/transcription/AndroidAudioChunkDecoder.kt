package com.suiji.app.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

class AndroidAudioChunkDecoder {
    fun decode(
        audioFile: File,
        chunkSeconds: Int = 30,
        targetSampleRate: Int = 16_000,
        onChunk: (FloatArray, Int) -> Unit
    ) {
        require(audioFile.isFile) { "Audio file is missing" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track was found")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type is missing")
            extractor.selectTrack(trackIndex)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            var sampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channelCount = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var accumulator = FloatAccumulator(sampleRate * chunkSeconds)
            var inputEnded = false
            var outputEnded = false
            val bufferInfo = MediaCodec.BufferInfo()

            fun emitFullChunks() {
                while (accumulator.size >= sampleRate * chunkSeconds) {
                    val source = accumulator.removeFirst(sampleRate * chunkSeconds)
                    onChunk(resample(source, sampleRate, targetSampleRate), targetSampleRate)
                }
            }

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer is missing")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        val newRate = newFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val newChannels = newFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        val newEncoding = newFormat.integerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (newRate != sampleRate && accumulator.size > 0) {
                            onChunk(
                                resample(accumulator.removeAll(), sampleRate, targetSampleRate),
                                targetSampleRate
                            )
                        }
                        if (newRate != sampleRate) {
                            accumulator = FloatAccumulator(newRate * chunkSeconds)
                        }
                        sampleRate = newRate
                        channelCount = newChannels
                        pcmEncoding = newEncoding
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: error("Decoder output buffer is missing")
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            accumulator.append(
                                decodeMonoPcm(outputBuffer.slice(), pcmEncoding, channelCount)
                            )
                            emitFullChunks()
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (accumulator.size > 0) {
                onChunk(
                    resample(accumulator.removeAll(), sampleRate, targetSampleRate),
                    targetSampleRate
                )
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun decodeMonoPcm(
        source: ByteBuffer,
        encoding: Int,
        channelCount: Int
    ): FloatArray {
        require(channelCount > 0) { "Invalid audio channel count" }
        source.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Unsupported PCM encoding: $encoding")
        }
        val frameCount = source.remaining() / (bytesPerSample * channelCount)
        val result = FloatArray(frameCount)
        repeat(frameCount) { frame ->
            var mixed = 0f
            repeat(channelCount) {
                mixed += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT ->
                        ((source.get().toInt() and 0xff) - 128) / 128f

                    AudioFormat.ENCODING_PCM_16BIT -> source.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val b0 = source.get().toInt() and 0xff
                        val b1 = source.get().toInt() and 0xff
                        val b2 = source.get().toInt()
                        val value = b0 or (b1 shl 8) or (b2 shl 16)
                        value / 8_388_608f
                    }

                    AudioFormat.ENCODING_PCM_32BIT -> source.int / 2_147_483_648f
                    AudioFormat.ENCODING_PCM_FLOAT -> source.float.coerceIn(-1f, 1f)
                    else -> 0f
                }
            }
            result[frame] = (mixed / channelCount).coerceIn(-1f, 1f)
        }
        return result
    }

    private fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        if (input.isEmpty() || inputRate == outputRate) return input
        val outputSize = ceil(input.size.toDouble() * outputRate / inputRate).toInt()
        return FloatArray(outputSize) { index ->
            val sourcePosition = index.toDouble() * inputRate / outputRate
            val lower = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val upper = (lower + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - lower).toFloat()
            input[lower] + (input[upper] - input[lower]) * fraction
        }
    }

    private fun MediaFormat.integerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private class FloatAccumulator(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1024))
        var size: Int = 0
            private set

        fun append(newValues: FloatArray) {
            ensureCapacity(size + newValues.size)
            newValues.copyInto(values, destinationOffset = size)
            size += newValues.size
        }

        fun removeFirst(count: Int): FloatArray {
            require(count in 0..size)
            val result = values.copyOfRange(0, count)
            values.copyInto(values, destinationOffset = 0, startIndex = count, endIndex = size)
            size -= count
            return result
        }

        fun removeAll(): FloatArray = removeFirst(size)

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var capacity = values.size
            while (capacity < required) capacity = capacity * 2
            values = values.copyOf(capacity)
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
