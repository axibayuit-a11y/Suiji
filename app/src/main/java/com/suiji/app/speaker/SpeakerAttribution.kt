package com.suiji.app.speaker

import com.suiji.app.model.TranscriptSegment
import com.suiji.app.model.TranscriptionResult

/**
 * 组合层：把独立说话人时间段附加到已经完成的本地转录结果。
 * 两个模型互不引用，后续更换任意一侧都不影响另一侧。
 */
object SpeakerAttribution {
    fun apply(
        transcription: TranscriptionResult,
        speakerRanges: List<SpeakerTimeRange>
    ): TranscriptionResult {
        if (speakerRanges.isEmpty()) return transcription
        val segments = transcription.segments.map { segment ->
            segment.copy(speakerId = bestSpeakerFor(segment, speakerRanges))
        }
        return transcription.copy(segments = segments)
    }

    private fun bestSpeakerFor(
        segment: TranscriptSegment,
        ranges: List<SpeakerTimeRange>
    ): String? = ranges
        .map { range ->
            val overlap = minOf(segment.endMs, range.endMs) -
                maxOf(segment.startMs, range.startMs)
            range.speakerId to overlap.coerceAtLeast(0L)
        }
        .maxByOrNull { it.second }
        ?.takeIf { it.second > 0L }
        ?.first
}
