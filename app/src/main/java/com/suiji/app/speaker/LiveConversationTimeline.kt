package com.suiji.app.speaker

import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import java.util.UUID

/** Joins delayed live ASR chunks with the overlapping LS-EEND activity frames. */
class LiveConversationTimeline {
    private data class SpeechChunk(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speakerId: String?
    )

    private val committed = mutableListOf<SpeechChunk>()
    private val speakerActivity = mutableListOf<SpeakerActivityFrame>()
    private var partial: SpeechChunk? = null
    private var speakerTrackingEnabled = false

    @Synchronized
    fun enableSpeakerTracking() {
        speakerTrackingEnabled = true
    }

    @Synchronized
    fun observeSpeakerActivity(frame: SpeakerActivityFrame) {
        if (!speakerTrackingEnabled) return
        speakerActivity += frame
        val keepAfter = frame.endMs - ACTIVITY_RETENTION_MS
        speakerActivity.removeAll { it.endMs < keepAfter }

        for (index in committed.indices) {
            val chunk = committed[index]
            if (chunk.endMs >= frame.startMs - FRAME_TOLERANCE_MS) {
                committed[index] = chunk.copy(
                    speakerId = speakerFor(chunk.startMs, chunk.endMs) ?: chunk.speakerId
                )
            }
        }
        partial = partial?.let { chunk ->
            chunk.copy(speakerId = speakerFor(chunk.startMs, chunk.endMs) ?: chunk.speakerId)
        }
    }

    @Synchronized
    fun updatePartial(startMs: Long, endMs: Long, text: String) {
        if (text.isBlank()) return
        val normalizedStart = startMs.coerceAtLeast(0L)
        val normalizedEnd = endMs.coerceAtLeast(normalizedStart)
        partial = SpeechChunk(
            id = partial?.id ?: UUID.randomUUID().toString(),
            startMs = normalizedStart,
            endMs = normalizedEnd,
            text = text.trim(),
            speakerId = speakerFor(normalizedStart, normalizedEnd)
        )
    }

    @Synchronized
    fun commit(startMs: Long, endMs: Long, text: String) {
        val resolvedText = text.trim().ifBlank { partial?.text.orEmpty() }
        partial = null
        if (resolvedText.isBlank()) return
        val normalizedStart = startMs.coerceAtLeast(0L)
        val normalizedEnd = endMs.coerceAtLeast(normalizedStart)
        committed += SpeechChunk(
            id = UUID.randomUUID().toString(),
            startMs = normalizedStart,
            endMs = normalizedEnd,
            text = resolvedText,
            speakerId = speakerFor(normalizedStart, normalizedEnd)
        )
    }

    @Synchronized
    fun visibleEvents(): List<TimelineEvent> {
        val chunks = (committed + listOfNotNull(partial)).sortedBy(SpeechChunk::startMs)
        if (chunks.isEmpty()) return emptyList()
        val visible = mutableListOf<TimelineEvent>()
        for (chunk in chunks) {
            val previous = visible.lastOrNull()
            if (previous != null && previous.speakerId == chunk.speakerId) {
                visible[visible.lastIndex] = previous.copy(
                    endTimestampMs = maxOf(previous.endTimestampMs, chunk.endMs),
                    text = joinText(previous.text, chunk.text)
                )
            } else {
                visible += TimelineEvent(
                    id = chunk.id,
                    type = TimelineEventType.SPEECH,
                    timestampMs = chunk.startMs,
                    endTimestampMs = chunk.endMs,
                    text = chunk.text,
                    speakerId = chunk.speakerId
                )
            }
        }
        val labels = SpeakerLabelNormalizer.mappingByFirstAppearance(
            visible.mapNotNull(TimelineEvent::speakerId)
        )
        return visible.map { event ->
            event.copy(speakerId = event.speakerId?.let(labels::getValue))
        }
    }

    private fun speakerFor(startMs: Long, endMs: Long): String? {
        if (!speakerTrackingEnabled || endMs <= startMs) return null
        val scores = FloatArray(LsEendStreamingModel.MAX_SPEAKERS)
        var totalWeight = 0f
        speakerActivity.forEach { frame ->
            val overlap = minOf(endMs, frame.endMs) - maxOf(startMs, frame.startMs)
            if (overlap <= 0L) return@forEach
            val weight = overlap.toFloat()
            frame.probabilities.forEachIndexed { index, probability ->
                scores[index] += probability * weight
            }
            totalWeight += weight
        }
        if (totalWeight == 0f) return null
        val speakerIndex = scores.indices.maxByOrNull(scores::get) ?: return null
        val averageProbability = scores[speakerIndex] / totalWeight
        return if (averageProbability >= SPEAKER_THRESHOLD) {
            "lseend_slot_$speakerIndex"
        } else {
            null
        }
    }

    private fun joinText(first: String, second: String): String {
        if (first.isBlank()) return second.trim()
        if (second.isBlank()) return first.trim()
        val needsSpace = first.last().isLetterOrDigit() &&
            second.first().isLetterOrDigit() &&
            first.last().code < 128 && second.first().code < 128
        return first.trimEnd() + (if (needsSpace) " " else "") + second.trimStart()
    }

    private companion object {
        const val SPEAKER_THRESHOLD = 0.5f
        const val FRAME_TOLERANCE_MS = 100L
        const val ACTIVITY_RETENTION_MS = 60_000L
    }
}
