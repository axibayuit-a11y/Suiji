package com.suiji.app.speaker

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.suiji.app.model.SpeakerDiarizationModelId
import kotlin.math.sqrt

sealed interface SpeakerTrackingResult {
    val currentSpeakerId: String

    data class Stable(
        override val currentSpeakerId: String
    ) : SpeakerTrackingResult

    data class Pending(
        override val currentSpeakerId: String,
        val candidateSpeakerId: String,
        val boundaryMs: Long
    ) : SpeakerTrackingResult

    data class Confirmed(
        val previousSpeakerId: String,
        override val currentSpeakerId: String,
        val boundaryMs: Long
    ) : SpeakerTrackingResult
}

/**
 * Adds hysteresis to speaker changes. A different voice is only confirmed after
 * repeated, sustained evidence; until then the UI keeps assigning text to the
 * currently confirmed speaker.
 */
class DelayedSpeakerConfirmation(
    initialSpeakerId: String = "speaker_0",
    private val requiredObservations: Int = 3,
    private val minimumEvidenceMs: Long = 1_200L
) {
    private var currentSpeakerId = initialSpeakerId
    private var candidateSpeakerId: String? = null
    private var candidateBoundaryMs = 0L
    private var candidateEndMs = 0L
    private var candidateObservations = 0

    val confirmedSpeakerId: String
        get() = currentSpeakerId

    fun keepCurrent(): SpeakerTrackingResult.Stable {
        clearCandidate()
        return SpeakerTrackingResult.Stable(currentSpeakerId)
    }

    fun observe(proposedSpeakerId: String, startMs: Long, endMs: Long): SpeakerTrackingResult {
        if (proposedSpeakerId == currentSpeakerId) {
            clearCandidate()
            return SpeakerTrackingResult.Stable(currentSpeakerId)
        }

        if (candidateSpeakerId != proposedSpeakerId) {
            candidateSpeakerId = proposedSpeakerId
            candidateBoundaryMs = startMs + (endMs - startMs).coerceAtLeast(0L) / 2L
            candidateEndMs = endMs
            candidateObservations = 1
        } else {
            candidateEndMs = maxOf(candidateEndMs, endMs)
            candidateObservations += 1
        }

        val candidate = checkNotNull(candidateSpeakerId)
        val sustained = candidateEndMs - candidateBoundaryMs >= minimumEvidenceMs
        if (candidateObservations < requiredObservations || !sustained) {
            return SpeakerTrackingResult.Pending(currentSpeakerId, candidate, candidateBoundaryMs)
        }

        val previous = currentSpeakerId
        currentSpeakerId = candidate
        val boundary = candidateBoundaryMs
        clearCandidate()
        return SpeakerTrackingResult.Confirmed(previous, currentSpeakerId, boundary)
    }

    private fun clearCandidate() {
        candidateSpeakerId = null
        candidateBoundaryMs = 0L
        candidateEndMs = 0L
        candidateObservations = 0
    }
}

/**
 * Independent live speaker tracker. It only consumes PCM and emits delayed
 * speaker decisions; it never calls or waits for the transcription engine.
 */
class LiveSpeakerAttributor(
    modelManager: SpeakerDiarizationModelManager,
    modelId: SpeakerDiarizationModelId
) : AutoCloseable {
    private data class Profile(var centroid: FloatArray, var observations: Int)

    private val profiles = linkedMapOf<String, Profile>()
    private val extractor: SpeakerEmbeddingExtractor
    private val confirmation = DelayedSpeakerConfirmation()
    private var pendingSpeakerId: String? = null
    private var pendingCentroid: FloatArray? = null
    private var pendingObservations = 0

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

    fun observe(samples: FloatArray, startMs: Long, endMs: Long): SpeakerTrackingResult {
        val embedding = extract(samples) ?: return SpeakerTrackingResult.Stable(currentSpeakerId())
        if (profiles.isEmpty()) {
            profiles[INITIAL_SPEAKER_ID] = Profile(embedding, 1)
            return SpeakerTrackingResult.Stable(INITIAL_SPEAKER_ID)
        }

        val currentId = currentSpeakerId()
        val currentScore = profiles[currentId]?.let { cosine(it.centroid, embedding) } ?: -1f
        val otherMatch = profiles
            .filterKeys { it != currentId }
            .maxByOrNull { cosine(it.value.centroid, embedding) }
        val otherScore = otherMatch?.let { cosine(it.value.centroid, embedding) } ?: -1f

        val proposedId = when {
            currentScore >= KEEP_CURRENT_THRESHOLD -> currentId
            otherMatch != null &&
                otherScore >= MATCH_EXISTING_THRESHOLD &&
                otherScore - currentScore >= CHANGE_MARGIN -> otherMatch.key
            currentScore < NEW_SPEAKER_THRESHOLD -> "speaker_${profiles.size}"
            else -> currentId
        }

        val decision = confirmation.observe(proposedId, startMs, endMs)
        when (decision) {
            is SpeakerTrackingResult.Stable -> {
                clearPending()
                if (currentScore >= KEEP_CURRENT_THRESHOLD) {
                    profiles[decision.currentSpeakerId]?.let { update(it, embedding) }
                }
            }

            is SpeakerTrackingResult.Pending -> accumulatePending(
                decision.candidateSpeakerId,
                embedding
            )

            is SpeakerTrackingResult.Confirmed -> {
                val confirmedEmbedding = pendingCentroid ?: embedding
                val profile = profiles[decision.currentSpeakerId]
                if (profile == null) {
                    profiles[decision.currentSpeakerId] = Profile(confirmedEmbedding, 1)
                } else {
                    update(profile, confirmedEmbedding)
                }
                clearPending()
            }
        }
        return decision
    }

    fun resetPendingEvidence() {
        confirmation.keepCurrent()
        clearPending()
    }

    private fun currentSpeakerId(): String = confirmation.confirmedSpeakerId

    private fun extract(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_EMBEDDING_SAMPLES) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) null else normalize(extractor.compute(stream))
        } finally {
            stream.release()
        }?.takeIf { it.isNotEmpty() }
    }

    private fun accumulatePending(speakerId: String, embedding: FloatArray) {
        if (pendingSpeakerId != speakerId || pendingCentroid == null) {
            pendingSpeakerId = speakerId
            pendingCentroid = embedding.copyOf()
            pendingObservations = 1
            return
        }
        val centroid = checkNotNull(pendingCentroid)
        val weight = pendingObservations.toFloat()
        centroid.indices.forEach { index ->
            centroid[index] = (centroid[index] * weight + embedding[index]) / (weight + 1f)
        }
        pendingCentroid = normalize(centroid)
        pendingObservations += 1
    }

    private fun clearPending() {
        pendingSpeakerId = null
        pendingCentroid = null
        pendingObservations = 0
    }

    private fun update(profile: Profile, embedding: FloatArray) {
        val weight = profile.observations.coerceAtMost(MAX_PROFILE_WEIGHT).toFloat()
        profile.centroid.indices.forEach { index ->
            profile.centroid[index] =
                (profile.centroid[index] * weight + embedding[index]) / (weight + 1f)
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
        const val INITIAL_SPEAKER_ID = "speaker_0"
        const val MIN_EMBEDDING_SAMPLES = SAMPLE_RATE * 3 / 4
        const val KEEP_CURRENT_THRESHOLD = 0.62f
        const val MATCH_EXISTING_THRESHOLD = 0.58f
        const val NEW_SPEAKER_THRESHOLD = 0.50f
        const val CHANGE_MARGIN = 0.07f
        const val MAX_PROFILE_WEIGHT = 8
    }
}
