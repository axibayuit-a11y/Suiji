package com.suiji.app.speaker

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveConversationTimelineTest {
    @Test
    fun movesProvisionalTextAfterSpeakerChangeIsConfirmed() {
        val timeline = LiveConversationTimeline("speaker_0")
        timeline.commit(0L, 1_000L, "第一位说话人的内容。")
        timeline.commit(2_000L, 2_600L, "这句话暂时放在当前人物。")
        timeline.updatePartial(2_600L, 3_200L, "正在实时增加的文字。")

        timeline.confirmSpeakerChange(
            previousSpeakerId = "speaker_0",
            newSpeakerId = "speaker_1",
            boundaryMs = 1_800L
        )

        val visible = timeline.visibleEvents()
        assertEquals(2, visible.size)
        assertEquals("speaker_0", visible[0].speakerId)
        assertEquals("第一位说话人的内容。", visible[0].text)
        assertEquals("speaker_1", visible[1].speakerId)
        assertEquals("这句话暂时放在当前人物。正在实时增加的文字。", visible[1].text)
        assertEquals(2_000L, visible[1].timestampMs)
    }

    @Test
    fun internalAsrChunksRemainOneVisibleSpeakerNode() {
        val timeline = LiveConversationTimeline("speaker_0")
        timeline.commit(0L, 1_000L, "第一段。")
        timeline.commit(1_500L, 2_500L, "第二段。")

        val visible = timeline.visibleEvents()

        assertEquals(1, visible.size)
        assertEquals("第一段。第二段。", visible.single().text)
        assertEquals(0L, visible.single().timestampMs)
        assertEquals(2_500L, visible.single().endTimestampMs)
    }

    @Test
    fun keepsLivePartialWhenFinalRecognizerReturnsBlank() {
        val timeline = LiveConversationTimeline("speaker_0")
        timeline.updatePartial(0L, 900L, "已经实时显示的文字。")

        timeline.commit(0L, 1_000L, "")

        val visible = timeline.visibleEvents()
        assertEquals(1, visible.size)
        assertEquals("已经实时显示的文字。", visible.single().text)
        assertEquals(1_000L, visible.single().endTimestampMs)
    }

    @Test
    fun visibleLabelsRestartAndRemainContinuousForEachTimeline() {
        val timeline = LiveConversationTimeline("native_speaker_3")
        timeline.commit(0L, 1_000L, "第一位。")
        timeline.confirmSpeakerChange(
            previousSpeakerId = "native_speaker_3",
            newSpeakerId = "native_speaker_7",
            boundaryMs = 1_200L
        )
        timeline.commit(1_300L, 2_000L, "第二位。")

        val visible = timeline.visibleEvents()

        assertEquals(listOf("speaker_0", "speaker_1"), visible.map { it.speakerId })
    }
}
