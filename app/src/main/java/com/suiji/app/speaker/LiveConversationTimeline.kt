package com.suiji.app.speaker

import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import java.util.UUID

/**
 * Keeps ASR chunks separate internally while exposing one visible node per
 * confirmed speaker turn. This lets a delayed speaker decision move recent text
 * without retranscribing or blocking live ASR.
 */
class LiveConversationTimeline(initialSpeakerId: String? = null) {
    private data class SpeakerTurn(val boundaryMs: Long, val speakerId: String)

    private data class SpeechChunk(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speakerId: String?
    )

    private val committed = mutableListOf<SpeechChunk>()
    private val speakerTurns = mutableListOf<SpeakerTurn>()
    private var partial: SpeechChunk? = null
    private var currentSpeakerId: String? = initialSpeakerId

    init {
        initialSpeakerId?.let { speakerTurns += SpeakerTurn(0L, it) }
    }

    @Synchronized
    fun enableSpeakerTracking(initialSpeakerId: String = "speaker_0") {
        currentSpeakerId = initialSpeakerId
        if (speakerTurns.isEmpty()) speakerTurns += SpeakerTurn(0L, initialSpeakerId)
        for (index in committed.indices) {
            if (committed[index].speakerId == null) {
                committed[index] = committed[index].copy(speakerId = initialSpeakerId)
            }
        }
        partial = partial?.copy(speakerId = initialSpeakerId)
    }

    @Synchronized
    fun updatePartial(startMs: Long, endMs: Long, text: String) {
        if (text.isBlank()) return
        partial = SpeechChunk(
            id = partial?.id ?: UUID.randomUUID().toString(),
            startMs = startMs.coerceAtLeast(0L),
            endMs = endMs.coerceAtLeast(startMs),
            text = text.trim(),
            speakerId = speakerAt(endMs)
        )
    }

    @Synchronized
    fun commit(startMs: Long, endMs: Long, text: String) {
        val resolvedText = text.trim().ifBlank { partial?.text.orEmpty() }
        partial = null
        if (resolvedText.isBlank()) return
        committed += SpeechChunk(
            id = UUID.randomUUID().toString(),
            startMs = startMs.coerceAtLeast(0L),
            endMs = endMs.coerceAtLeast(startMs),
            text = resolvedText,
            speakerId = speakerAt(startMs + (endMs - startMs) / 2L)
        )
    }

    @Synchronized
    fun confirmSpeakerChange(
        previousSpeakerId: String,
        newSpeakerId: String,
        boundaryMs: Long
    ) {
        for (index in committed.indices) {
            val chunk = committed[index]
            val midpoint = chunk.startMs + (chunk.endMs - chunk.startMs) / 2L
            if (chunk.speakerId == previousSpeakerId && midpoint >= boundaryMs) {
                committed[index] = chunk.copy(speakerId = newSpeakerId)
            }
        }
        partial = partial?.let { chunk ->
            if (chunk.speakerId == previousSpeakerId && chunk.endMs > boundaryMs) {
                chunk.copy(speakerId = newSpeakerId)
            } else {
                chunk
            }
        }
        currentSpeakerId = newSpeakerId
        speakerTurns.removeAll { it.boundaryMs >= boundaryMs }
        speakerTurns += SpeakerTurn(boundaryMs, newSpeakerId)
        speakerTurns.sortBy(SpeakerTurn::boundaryMs)
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
        return visible
    }

    private fun speakerAt(timestampMs: Long): String? = speakerTurns
        .lastOrNull { it.boundaryMs <= timestampMs }
        ?.speakerId
        ?: currentSpeakerId

    private fun joinText(first: String, second: String): String {
        if (first.isBlank()) return second.trim()
        if (second.isBlank()) return first.trim()
        val needsSpace = first.last().isLetterOrDigit() &&
            second.first().isLetterOrDigit() &&
            first.last().code < 128 && second.first().code < 128
        return first.trimEnd() + (if (needsSpace) " " else "") + second.trimStart()
    }
}
