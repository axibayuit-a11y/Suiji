package com.suiji.app.speaker

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import com.suiji.app.model.SpeakerDiarizationModelId

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

    /** Existing enrolled speakers are trusted immediately. The embedding manager
     * has already matched them; waiting for another utterance creates a full-turn lag. */
    fun confirmExisting(proposedSpeakerId: String, boundaryMs: Long): SpeakerTrackingResult {
        if (proposedSpeakerId == currentSpeakerId) {
            clearCandidate()
            return SpeakerTrackingResult.Stable(currentSpeakerId)
        }
        val previous = currentSpeakerId
        currentSpeakerId = proposedSpeakerId
        clearCandidate()
        return SpeakerTrackingResult.Confirmed(previous, currentSpeakerId, boundaryMs)
    }

    private fun clearCandidate() {
        candidateSpeakerId = null
        candidateBoundaryMs = 0L
        candidateEndMs = 0L
        candidateObservations = 0
    }
}

/**
 * Independent live speaker tracker. It immediately accepts matches to enrolled
 * speakers, but delays registration of a previously unseen voice. It never calls
 * or waits for the transcription engine.
 */
class LiveSpeakerAttributor(
    modelManager: SpeakerDiarizationModelManager,
    modelId: SpeakerDiarizationModelId
) : AutoCloseable {
    private val extractor: SpeakerEmbeddingExtractor
    private val manager: SpeakerEmbeddingManager
    private val confirmation = DelayedSpeakerConfirmation(requiredObservations = 2)
    private var pendingSpeakerId: String? = null
    private var pendingNewSpeakerId: String? = null
    private val pendingEmbeddings = mutableListOf<FloatArray>()
    private var nextNewSpeakerIndex = 1

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
        manager = SpeakerEmbeddingManager(extractor.dim())
    }

    fun observe(samples: FloatArray, startMs: Long, endMs: Long): SpeakerTrackingResult {
        val embedding = extract(samples) ?: return SpeakerTrackingResult.Stable(currentSpeakerId())
        if (manager.numSpeakers() == 0) {
            check(manager.add(INITIAL_SPEAKER_ID, embedding)) {
                "Could not register the first speaker"
            }
            return SpeakerTrackingResult.Stable(INITIAL_SPEAKER_ID)
        }

        // This is the same registration/search pattern used by the official
        // sherpa-onnx dynamic speaker-identification example: search all enrolled
        // speakers first, and only allocate the next consecutive ID on no match.
        val matchedSpeaker = manager.search(embedding, SPEAKER_MATCH_THRESHOLD)
        val proposedId = matchedSpeaker.ifBlank {
            pendingNewSpeakerId ?: "speaker_$nextNewSpeakerIndex"
        }
        val decision = if (matchedSpeaker.isNotBlank()) {
            confirmation.confirmExisting(
                proposedSpeakerId = proposedId,
                boundaryMs = startMs + (endMs - startMs).coerceAtLeast(0L) / 2L
            )
        } else {
            confirmation.observe(proposedId, startMs, endMs)
        }
        when (decision) {
            is SpeakerTrackingResult.Stable -> clearPending()

            is SpeakerTrackingResult.Pending -> accumulatePending(
                decision.candidateSpeakerId,
                embedding
            )

            is SpeakerTrackingResult.Confirmed -> {
                if (!manager.contains(decision.currentSpeakerId)) {
                    val enrollment = (pendingEmbeddings + embedding).toTypedArray()
                    check(manager.add(decision.currentSpeakerId, enrollment)) {
                        "Could not register ${decision.currentSpeakerId}"
                    }
                    nextNewSpeakerIndex += 1
                }
                clearPending()
            }
        }
        return decision
    }

    private fun currentSpeakerId(): String = confirmation.confirmedSpeakerId

    private fun extract(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_EMBEDDING_SAMPLES) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) null else extractor.compute(stream)
        } finally {
            stream.release()
        }?.takeIf { it.isNotEmpty() }
    }

    private fun accumulatePending(speakerId: String, embedding: FloatArray) {
        pendingNewSpeakerId = speakerId
        if (pendingSpeakerId != speakerId) {
            pendingSpeakerId = speakerId
            pendingEmbeddings.clear()
            pendingEmbeddings += embedding
            return
        }
        pendingEmbeddings += embedding
    }

    private fun clearPending() {
        pendingSpeakerId = null
        pendingNewSpeakerId = null
        pendingEmbeddings.clear()
    }

    override fun close() {
        manager.release()
        extractor.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val INITIAL_SPEAKER_ID = "speaker_0"
        const val MIN_EMBEDDING_SAMPLES = SAMPLE_RATE * 3 / 4
        const val SPEAKER_MATCH_THRESHOLD = 0.50f
    }
}
