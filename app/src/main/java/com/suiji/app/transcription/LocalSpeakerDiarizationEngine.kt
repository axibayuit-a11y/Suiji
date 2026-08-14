package com.suiji.app.transcription

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.suiji.app.model.LocalModelId
import com.suiji.app.model.LocalModelDescriptor
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.TranscriptSegment
import com.suiji.app.model.TranscriptionResult
import java.io.File

class LocalSpeakerDiarizationEngine(
    private val modelManager: LocalModelManager,
    private val audioDecoder: AndroidAudioChunkDecoder = AndroidAudioChunkDecoder()
) {
    fun isInstalled(): Boolean {
        val descriptor = modelManager.descriptor(LocalModelId.SPEAKER_DIARIZATION)
        return modelManager.isInstalled(descriptor)
    }

    fun diarizeAndTranscribe(
        audioFile: File,
        asrDescriptor: LocalModelDescriptor,
        language: RecordingLanguage,
        asrEngine: SenseVoiceLocalTranscriptionEngine
    ): Result<TranscriptionResult> = runCatching {
        require(isInstalled()) { "Speaker diarization model is not installed" }
        val directory = modelManager.modelDirectory(LocalModelId.SPEAKER_DIARIZATION)
        val chunks = mutableListOf<FloatArray>()
        var totalSamples = 0
        audioDecoder.decode(audioFile, chunkSeconds = 60, targetSampleRate = SAMPLE_RATE) { samples, _ ->
            if (samples.isNotEmpty()) {
                chunks += samples
                totalSamples += samples.size
            }
        }
        require(totalSamples > 0) { "Recording contains no audio samples" }
        val samples = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(samples, destinationOffset = offset)
            offset += chunk.size
        }

        val diarizer = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        File(directory, SEGMENTATION_FILENAME).absolutePath
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = File(directory, EMBEDDING_FILENAME).absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false
                ),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f
            )
        )
        try {
            val speakerSegments = mergeAdjacent(diarizer.process(samples).map {
                SpeakerRange(
                    startMs = (it.start * 1000f).toLong(),
                    endMs = (it.end * 1000f).toLong(),
                    speakerId = "speaker_${it.speaker}"
                )
            })
            val recognizer = asrEngine.createRecognizer(asrDescriptor, language)
            val assigned = try {
                speakerSegments.mapNotNull { speaker ->
                    val startSample = (speaker.startMs * SAMPLE_RATE / 1000L)
                        .toInt().coerceIn(0, samples.size)
                    val endSample = (speaker.endMs * SAMPLE_RATE / 1000L)
                        .toInt().coerceIn(startSample, samples.size)
                    if (endSample - startSample < MIN_SEGMENT_SAMPLES) return@mapNotNull null
                    val text = asrEngine.transcribeSamples(
                        recognizer,
                        samples.copyOfRange(startSample, endSample),
                        SAMPLE_RATE
                    )
                    text.takeIf(String::isNotBlank)?.let {
                        TranscriptSegment(
                            startMs = speaker.startMs,
                            endMs = speaker.endMs,
                            text = it,
                            speakerId = speaker.speakerId
                        )
                    }
                }
            } finally {
                recognizer.release()
            }
            val text = assigned.joinToString("\n") { segment ->
                segment.speakerId?.let { "$it: ${segment.text}" } ?: segment.text
            }
            TranscriptionResult(text = text, segments = assigned)
        } finally {
            diarizer.release()
        }
    }

    private fun mergeAdjacent(ranges: List<SpeakerRange>): List<SpeakerRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy(SpeakerRange::startMs)
        val merged = mutableListOf<SpeakerRange>()
        for (range in sorted) {
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                previous.speakerId == range.speakerId &&
                range.startMs - previous.endMs <= MERGE_GAP_MS
            ) {
                merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, range.endMs))
            } else {
                merged += range
            }
        }
        return merged
    }

    private data class SpeakerRange(
        val startMs: Long,
        val endMs: Long,
        val speakerId: String
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SEGMENTATION_FILENAME = "segmentation.onnx"
        const val EMBEDDING_FILENAME = "embedding.onnx"
        const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE / 5
        const val MERGE_GAP_MS = 300L
    }
}
