package com.suiji.app.speaker

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerLabelNormalizerTest {
    @Test
    fun renamesAnonymousClustersByChronologicalFirstAppearance() {
        val normalized = SpeakerLabelNormalizer.byFirstAppearance(
            listOf(
                SpeakerTimeRange(8_000L, 16_000L, "speaker_2"),
                SpeakerTimeRange(0L, 8_000L, "speaker_1"),
                SpeakerTimeRange(24_000L, 32_000L, "speaker_5"),
                SpeakerTimeRange(16_000L, 24_000L, "speaker_1")
            )
        )

        assertEquals(listOf(0L, 8_000L, 16_000L, 24_000L), normalized.map { it.startMs })
        assertEquals(
            listOf("speaker_0", "speaker_1", "speaker_0", "speaker_2"),
            normalized.map { it.speakerId }
        )
    }

    @Test
    fun keepsRepeatedSpeakersStableWithoutSkippedNumbers() {
        val normalized = SpeakerLabelNormalizer.byFirstAppearance(
            listOf(
                SpeakerTimeRange(0L, 5_000L, "speaker_5"),
                SpeakerTimeRange(5_000L, 10_000L, "speaker_2"),
                SpeakerTimeRange(10_000L, 15_000L, "speaker_5"),
                SpeakerTimeRange(15_000L, 20_000L, "speaker_6")
            )
        )

        assertEquals(
            listOf("speaker_0", "speaker_1", "speaker_0", "speaker_2"),
            normalized.map { it.speakerId }
        )
    }

    @Test
    fun exposesMappingForMigratingStoredRecordingsAndSpeakerNames() {
        assertEquals(
            mapOf(
                "speaker_5" to "speaker_0",
                "speaker_2" to "speaker_1",
                "speaker_6" to "speaker_2"
            ),
            SpeakerLabelNormalizer.mappingByFirstAppearance(
                listOf("speaker_5", "speaker_2", "speaker_5", "speaker_6")
            )
        )
    }
}
