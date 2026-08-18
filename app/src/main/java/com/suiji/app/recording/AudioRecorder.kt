package com.suiji.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

class AudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var output: BufferedOutputStream? = null
    private var outputFile: File? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var writtenSamples = 0L

    @Volatile
    private var recordingFailure: Throwable? = null

    val elapsedMs: Long
        get() = writtenSamples * 1000L / SAMPLE_RATE

    val isActive: Boolean
        get() = running

    @SuppressLint("MissingPermission")
    fun start(
        recordingId: String,
        onAudioFrame: (AudioFrame) -> Unit = {}
    ): Result<File> {
        release()
        val recordingsDirectory = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(recordingsDirectory, "$recordingId.wav")
        return runCatching {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBufferBytes > 0) { "The microphone does not support PCM recording" }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferBytes * 2, FRAME_SAMPLES * 4)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Could not initialize the microphone"
            }

            val stream = BufferedOutputStream(FileOutputStream(file))
            stream.write(ByteArray(WAV_HEADER_BYTES))
            audioRecord = recorder
            output = stream
            outputFile = file
            writtenSamples = 0L
            recordingFailure = null
            paused = false
            running = true
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "The microphone did not start"
            }

            recordingThread = Thread(
                { captureLoop(recorder, stream, onAudioFrame) },
                "suiji-audio-capture"
            ).apply { start() }
            file
        }.onFailure {
            file.delete()
            release()
        }
    }

    fun pause(): Result<Unit> = runCatching {
        check(running && !paused) { "Recorder is not active" }
        paused = true
    }

    fun resume(): Result<Unit> = runCatching {
        check(running && paused) { "Recorder is not paused" }
        paused = false
    }

    fun stop(): Result<RecordedAudio> {
        val file = outputFile
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { recordingThread?.join(2_000) }
        runCatching { output?.flush() }
        runCatching { output?.close() }
        runCatching { audioRecord?.release() }
        val duration = elapsedMs
        val sampleCount = writtenSamples
        val failure = recordingFailure
        clearReferences()

        return runCatching {
            if (failure != null) throw failure
            val completedFile = checkNotNull(file)
            check(sampleCount > 0) { "No microphone samples were recorded" }
            writeWavHeader(completedFile, sampleCount)
            RecordedAudio(completedFile, duration)
        }.onFailure { file?.delete() }
    }

    fun release() {
        if (audioRecord != null || output != null) {
            running = false
            runCatching { audioRecord?.stop() }
            runCatching { recordingThread?.join(1_000) }
            runCatching { output?.close() }
            runCatching { audioRecord?.release() }
        }
        clearReferences()
    }

    private fun captureLoop(
        recorder: AudioRecord,
        stream: BufferedOutputStream,
        onAudioFrame: (AudioFrame) -> Unit
    ) {
        val samples = ShortArray(FRAME_SAMPLES)
        val pcmBytes = ByteArray(FRAME_SAMPLES * 2)
        var framesSinceCheckpoint = 0
        var speakerDownsampleSum = 0f
        var speakerDownsampleCount = 0
        try {
            while (running) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (running) error("Microphone read failed: $count")
                    break
                }
                if (paused) continue

                val bytes = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { bytes.putShort(samples[it]) }
                stream.write(pcmBytes, 0, count * 2)
                writtenSamples += count
                framesSinceCheckpoint += 1
                if (framesSinceCheckpoint >= CHECKPOINT_EVERY_FRAMES) {
                    stream.flush()
                    outputFile?.let { writeWavHeader(it, writtenSamples) }
                    framesSinceCheckpoint = 0
                }

                val sumSquares = samples.take(count).sumOf {
                    val normalized = it / 32768.0
                    normalized * normalized
                }
                val rms = sqrt(sumSquares / count.coerceAtLeast(1))
                val visualLevel = ((20.0 * log10(rms.coerceAtLeast(0.00001)) + 55.0) / 55.0)
                    .toFloat()
                    .coerceIn(0.02f, 1f)
                val asrSamples = FloatArray((count + DOWNSAMPLE_FACTOR - 1) / DOWNSAMPLE_FACTOR) { index ->
                    samples[index * DOWNSAMPLE_FACTOR] / 32768.0f
                }
                val speakerBuffer = FloatArray(
                    (count + speakerDownsampleCount) / SPEAKER_DOWNSAMPLE_FACTOR
                )
                var speakerSize = 0
                repeat(count) { index ->
                    speakerDownsampleSum += samples[index] / 32768.0f
                    speakerDownsampleCount += 1
                    if (speakerDownsampleCount == SPEAKER_DOWNSAMPLE_FACTOR) {
                        speakerBuffer[speakerSize++] =
                            speakerDownsampleSum / SPEAKER_DOWNSAMPLE_FACTOR
                        speakerDownsampleSum = 0f
                        speakerDownsampleCount = 0
                    }
                }
                val speakerSamples = speakerBuffer.copyOf(speakerSize)
                onAudioFrame(
                    AudioFrame(visualLevel, rms.toFloat(), asrSamples, speakerSamples, elapsedMs)
                )
            }
        } catch (error: Throwable) {
            if (running) recordingFailure = error
        }
    }

    private fun writeWavHeader(file: File, sampleCount: Long) {
        val dataBytes = sampleCount * CHANNELS * BITS_PER_SAMPLE / 8
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.writeIntLittleEndian((36L + dataBytes).coerceAtMost(0xffffffffL).toInt())
            wav.writeBytes("WAVE")
            wav.writeBytes("fmt ")
            wav.writeIntLittleEndian(16)
            wav.writeShortLittleEndian(1)
            wav.writeShortLittleEndian(CHANNELS)
            wav.writeIntLittleEndian(SAMPLE_RATE)
            wav.writeIntLittleEndian(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
            wav.writeShortLittleEndian(CHANNELS * BITS_PER_SAMPLE / 8)
            wav.writeShortLittleEndian(BITS_PER_SAMPLE)
            wav.writeBytes("data")
            wav.writeIntLittleEndian(dataBytes.coerceAtMost(0xffffffffL).toInt())
        }
    }

    private fun RandomAccessFile.writeIntLittleEndian(value: Int) {
        writeInt(Integer.reverseBytes(value))
    }

    private fun RandomAccessFile.writeShortLittleEndian(value: Int) {
        writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
    }

    private fun clearReferences() {
        audioRecord = null
        output = null
        outputFile = null
        recordingThread = null
        running = false
        paused = false
        writtenSamples = 0L
        recordingFailure = null
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val ASR_SAMPLE_RATE = 16_000
        const val SPEAKER_SAMPLE_RATE = 8_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val FRAME_SAMPLES = SAMPLE_RATE / 10
        const val DOWNSAMPLE_FACTOR = SAMPLE_RATE / ASR_SAMPLE_RATE
        const val SPEAKER_DOWNSAMPLE_FACTOR = SAMPLE_RATE / SPEAKER_SAMPLE_RATE
        const val WAV_HEADER_BYTES = 44
        const val CHECKPOINT_EVERY_FRAMES = 10
    }
}

data class AudioFrame(
    val level: Float,
    val rms: Float,
    val asrSamples: FloatArray,
    val speakerSamples: FloatArray,
    val elapsedMs: Long
)

data class RecordedAudio(
    val file: File,
    val durationMs: Long
)
