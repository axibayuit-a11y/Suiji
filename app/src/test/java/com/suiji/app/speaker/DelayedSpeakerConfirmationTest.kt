package com.suiji.app.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedSpeakerConfirmationTest {
    @Test
    fun keepsCurrentSpeakerUntilDifferentVoiceIsSustained() {
        val confirmation = DelayedSpeakerConfirmation(
            requiredObservations = 3,
            minimumEvidenceMs = 1_200L
        )

        val first = confirmation.observe("speaker_1", 1_000L, 2_600L)
        val second = confirmation.observe("speaker_1", 1_600L, 3_200L)
        val third = confirmation.observe("speaker_1", 2_200L, 3_800L)

        assertTrue(first is SpeakerTrackingResult.Pending)
        assertTrue(second is SpeakerTrackingResult.Pending)
        assertTrue(third is SpeakerTrackingResult.Confirmed)
        third as SpeakerTrackingResult.Confirmed
        assertEquals("speaker_0", third.previousSpeakerId)
        assertEquals("speaker_1", third.currentSpeakerId)
        assertEquals(1_800L, third.boundaryMs)
    }

    @Test
    fun transientDifferenceDoesNotCreateSpeaker() {
        val confirmation = DelayedSpeakerConfirmation(
            requiredObservations = 3,
            minimumEvidenceMs = 1_200L
        )

        assertTrue(
            confirmation.observe("speaker_1", 1_000L, 2_600L) is
                SpeakerTrackingResult.Pending
        )
        assertTrue(
            confirmation.observe("speaker_0", 1_600L, 3_200L) is
                SpeakerTrackingResult.Stable
        )
        assertTrue(
            confirmation.observe("speaker_1", 4_000L, 5_600L) is
                SpeakerTrackingResult.Pending
        )
        assertTrue(
            confirmation.observe("speaker_1", 4_600L, 6_200L) is
                SpeakerTrackingResult.Pending
        )
        assertEquals("speaker_0", confirmation.confirmedSpeakerId)
    }
}
