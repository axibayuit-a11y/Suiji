package com.suiji.app.speaker

import com.suiji.app.model.TranscriptSegment
import com.suiji.app.model.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAttributionTest {
    @Test
    fun assignsSpeakerWithLargestTimeOverlap() {
        val transcription = TranscriptionResult(
            text = "第一段\n第二段",
            segments = listOf(
                TranscriptSegment(0, 4_000, "第一段"),
                TranscriptSegment(4_000, 8_000, "第二段")
            )
        )
        val ranges = listOf(
            SpeakerTimeRange(0, 3_500, "speaker_0"),
            SpeakerTimeRange(3_500, 8_000, "speaker_1")
        )

        val result = SpeakerAttribution.apply(transcription, ranges)

        assertEquals("speaker_0", result.segments[0].speakerId)
        assertEquals("speaker_1", result.segments[1].speakerId)
    }
}
