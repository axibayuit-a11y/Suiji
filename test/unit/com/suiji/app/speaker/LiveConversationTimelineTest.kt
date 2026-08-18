package com.suiji.app.speaker

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveConversationTimelineTest {
    @Test
    fun assignsTextToDominantRealtimeSpeakerTrack() {
        val timeline = LiveConversationTimeline()
        timeline.enableSpeakerTracking()
        repeat(10) { index ->
            timeline.observeSpeakerActivity(
                activity(index * 100L, speaker = 3)
            )
        }
        repeat(10) { index ->
            timeline.observeSpeakerActivity(
                activity(1_000L + index * 100L, speaker = 6)
            )
        }

        timeline.commit(0L, 1_000L, "第一位。")
        timeline.commit(1_000L, 2_000L, "第二位。")

        val visible = timeline.visibleEvents()
        assertEquals(listOf("speaker_0", "speaker_1"), visible.map { it.speakerId })
        assertEquals(listOf("第一位。", "第二位。"), visible.map { it.text })
    }

    @Test
    fun lateModelFramesCorrectAlreadyCommittedText() {
        val timeline = LiveConversationTimeline()
        timeline.enableSpeakerTracking()
        timeline.commit(0L, 800L, "模型结果稍后到达。")
        assertEquals(null, timeline.visibleEvents().single().speakerId)

        repeat(8) { index ->
            timeline.observeSpeakerActivity(activity(index * 100L, speaker = 5))
        }

        assertEquals("speaker_0", timeline.visibleEvents().single().speakerId)
    }

    @Test
    fun keepsBlankFinalRecognitionFromDiscardingLivePartial() {
        val timeline = LiveConversationTimeline()
        timeline.updatePartial(0L, 900L, "已经实时显示的文字。")
        timeline.commit(0L, 1_000L, "")

        assertEquals("已经实时显示的文字。", timeline.visibleEvents().single().text)
    }

    private fun activity(startMs: Long, speaker: Int): SpeakerActivityFrame {
        val probabilities = FloatArray(8) { 0.05f }
        probabilities[speaker] = 0.95f
        return SpeakerActivityFrame(startMs, startMs + 100L, probabilities)
    }
}
