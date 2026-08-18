package com.suiji.app.transcription

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.suiji.app.model.LocalModelDescriptor
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.TranscriptSegment
import com.suiji.app.model.TranscriptionResult
import java.io.File

class SenseVoiceLocalTranscriptionEngine(
    private val modelManager: LocalModelManager,
    context: Context,
    private val audioDecoder: AndroidAudioChunkDecoder = AndroidAudioChunkDecoder()
) {
    private val speechSegmenter = NaturalSpeechSegmenter(context)

    fun transcribe(
        audioFile: File,
        descriptor: LocalModelDescriptor,
        language: RecordingLanguage
    ): Result<String> = transcribeWithSegments(audioFile, descriptor, language).map { it.text }

    fun transcribeWithSegments(
        audioFile: File,
        descriptor: LocalModelDescriptor,
        language: RecordingLanguage
    ): Result<TranscriptionResult> = runCatching {
        val recognizer = createRecognizer(descriptor, language)
        try {
            val segments = mutableListOf<TranscriptSegment>()
            val vadSession = speechSegmenter.openSession()

            fun recognize(naturalSegment: NaturalSpeechSegment) {
                transcribeSamples(recognizer, naturalSegment.samples)
                    .takeIf(String::isNotBlank)
                    ?.let { text ->
                        segments += TranscriptSegment(
                            startMs = naturalSegment.startMs,
                            endMs = naturalSegment.endMs,
                            text = text
                        )
                    }
            }

            try {
                audioDecoder.decode(audioFile, chunkSeconds = DECODER_BUFFER_SECONDS) { samples, _ ->
                    vadSession.accept(samples).forEach(::recognize)
                }
                vadSession.flush().forEach(::recognize)
            } finally {
                vadSession.close()
            }
            val text = segments.joinToString(separator = "\n", transform = TranscriptSegment::text)
            require(text.isNotBlank()) { "The local model did not recognize speech" }
            TranscriptionResult(text, segments)
        } finally {
            recognizer.release()
        }
    }

    fun createRecognizer(
        descriptor: LocalModelDescriptor,
        language: RecordingLanguage
    ): OfflineRecognizer {
        require(modelManager.isInstalled(descriptor)) { "The selected local model is not installed" }
        val directory = modelManager.modelDirectory(descriptor.id)
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(directory, "model.int8.onnx").absolutePath,
                        language = language.toSenseVoiceLanguage(),
                        useInverseTextNormalization = descriptor.useInverseTextNormalization
                    ),
                    tokens = File(directory, "tokens.txt").absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false,
                    provider = "cpu"
                )
            )
        )
    }

    fun transcribeSamples(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        sampleRate: Int = 16_000
    ): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
                .takeIf { text -> text.any(Char::isLetterOrDigit) }
                .orEmpty()
        } finally {
            stream.release()
        }
    }

    private fun RecordingLanguage.toSenseVoiceLanguage(): String = when (this) {
        RecordingLanguage.CHINESE -> "zh"
        RecordingLanguage.ENGLISH -> "en"
        RecordingLanguage.CANTONESE_HK -> "yue"
    }

    private companion object {
        const val DECODER_BUFFER_SECONDS = 30
    }
}
