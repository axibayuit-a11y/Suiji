package com.suiji.app.speaker

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.suiji.app.model.SpeakerDiarizationModelId
import com.suiji.app.transcription.AndroidAudioChunkDecoder
import java.io.File

data class SpeakerTimeRange(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String
)

/**
 * 独立的说话人分离推理模块，只输出“谁在什么时候说话”。
 * 它不了解也不调用任何语音转录模型。
 */
class LocalSpeakerDiarizationEngine(
    private val modelManager: SpeakerDiarizationModelManager,
    private val audioDecoder: AndroidAudioChunkDecoder = AndroidAudioChunkDecoder()
) {
    fun diarize(
        audioFile: File,
        modelId: SpeakerDiarizationModelId
    ): Result<List<SpeakerTimeRange>> = runCatching {
        val descriptor = modelManager.descriptor(modelId)
        require(modelManager.isInstalled(descriptor)) { "Speaker diarization model is not installed" }
        val directory = modelManager.modelDirectory(modelId)
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
                        File(
                            directory,
                            SpeakerDiarizationModelManager.SEGMENTATION_FILENAME
                        ).absolutePath
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = File(
                        directory,
                        SpeakerDiarizationModelManager.EMBEDDING_FILENAME
                    ).absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false
                ),
                // sherpa-onnx uses a distance threshold: a smaller value creates
                // more clusters. Its official unknown-speaker-count example uses
                // 0.90. The previous 0.50 default severely over-split short phone
                // recordings when playback/channel conditions changed.
                clustering = FastClusteringConfig(
                    numClusters = -1,
                    threshold = CLUSTERING_THRESHOLD
                ),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f
            )
        )
        try {
            SpeakerLabelNormalizer.byFirstAppearance(mergeAdjacent(diarizer.process(samples).map {
                SpeakerTimeRange(
                    startMs = (it.start * 1000f).toLong(),
                    endMs = (it.end * 1000f).toLong(),
                    speakerId = "speaker_${it.speaker}"
                )
            }))
        } finally {
            diarizer.release()
        }
    }

    private fun mergeAdjacent(ranges: List<SpeakerTimeRange>): List<SpeakerTimeRange> {
        val merged = mutableListOf<SpeakerTimeRange>()
        for (range in ranges.sortedBy(SpeakerTimeRange::startMs)) {
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

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CLUSTERING_THRESHOLD = 0.90f
        const val MERGE_GAP_MS = 300L
    }
}

/**
 * Clustering labels are anonymous and their numeric values have no user-facing
 * meaning. Rename them in chronological first-appearance order so every
 * recording always starts at speaker_0 and never contains skipped numbers.
 */
object SpeakerLabelNormalizer {
    fun mappingByFirstAppearance(speakerIds: Iterable<String>): Map<String, String> {
        val labels = linkedMapOf<String, String>()
        speakerIds.forEach { speakerId ->
            labels.getOrPut(speakerId) { "speaker_${labels.size}" }
        }
        return labels
    }

    fun byFirstAppearance(ranges: List<SpeakerTimeRange>): List<SpeakerTimeRange> {
        val sorted = ranges.sortedBy(SpeakerTimeRange::startMs)
        val labels = mappingByFirstAppearance(sorted.map(SpeakerTimeRange::speakerId))
        return sorted.map { range ->
            range.copy(speakerId = labels.getValue(range.speakerId))
        }
    }
}
