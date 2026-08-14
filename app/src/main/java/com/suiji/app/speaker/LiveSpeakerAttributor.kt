package com.suiji.app.speaker

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.suiji.app.model.SpeakerDiarizationModelId
import kotlin.math.sqrt

/**
 * Assigns a stable speaker id when each natural speech turn closes. It is kept
 * independent from ASR: the only input is PCM and the only output is a speaker id.
 */
class LiveSpeakerAttributor(
    modelManager: SpeakerDiarizationModelManager,
    modelId: SpeakerDiarizationModelId
) : AutoCloseable {
    private data class Profile(var centroid: FloatArray, var observations: Int)

    private val profiles = linkedMapOf<String, Profile>()
    private val extractor: SpeakerEmbeddingExtractor
    private var lastSpeakerId: String? = null
    private var lastEndMs = Long.MIN_VALUE

    init {
        val descriptor = modelManager.descriptor(modelId)
        require(modelManager.isInstalled(descriptor)) { "Speaker model is not installed" }
        extractor = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = java.io.File(
                    modelManager.modelDirectory(modelId),
                    SpeakerDiarizationModelManager.EMBEDDING_FILENAME
                ).absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                debug = false,
                provider = "cpu"
            )
        )
    }

    fun attribute(samples: FloatArray, startMs: Long, endMs: Long): String? {
        if (samples.size < MIN_EMBEDDING_SAMPLES) {
            return lastSpeakerId?.takeIf { startMs - lastEndMs <= SHORT_TURN_REUSE_MS }
        }
        val stream = extractor.createStream()
        val embedding = try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            normalize(extractor.compute(stream))
        } finally {
            stream.release()
        }
        if (embedding.isEmpty()) return null

        val match = profiles.maxByOrNull { cosine(it.value.centroid, embedding) }
        val speakerId = if (match != null && cosine(match.value.centroid, embedding) >= MATCH_THRESHOLD) {
            update(match.value, embedding)
            match.key
        } else {
            "speaker_${profiles.size}".also { profiles[it] = Profile(embedding, 1) }
        }
        lastSpeakerId = speakerId
        lastEndMs = endMs
        return speakerId
    }

    private fun update(profile: Profile, embedding: FloatArray) {
        val weight = profile.observations.coerceAtMost(MAX_PROFILE_WEIGHT).toFloat()
        profile.centroid.indices.forEach { index ->
            profile.centroid[index] = (profile.centroid[index] * weight + embedding[index]) / (weight + 1f)
        }
        profile.centroid = normalize(profile.centroid)
        profile.observations += 1
    }

    private fun cosine(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size || first.isEmpty()) return -1f
        var dot = 0f
        first.indices.forEach { dot += first[it] * second[it] }
        return dot
    }

    private fun normalize(value: FloatArray): FloatArray {
        var squared = 0.0
        value.forEach { squared += it * it }
        val norm = sqrt(squared).toFloat()
        if (norm <= 1e-6f) return value
        return FloatArray(value.size) { value[it] / norm }
    }

    override fun close() {
        extractor.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_EMBEDDING_SAMPLES = SAMPLE_RATE * 3 / 4
        const val SHORT_TURN_REUSE_MS = 1_200L
        const val MATCH_THRESHOLD = 0.55f
        const val MAX_PROFILE_WEIGHT = 8
    }
}
