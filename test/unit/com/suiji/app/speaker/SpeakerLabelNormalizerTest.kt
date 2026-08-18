package com.suiji.app.speaker

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerLabelNormalizerTest {
    @Test
    fun mapsSparseModelSlotsByFirstAppearance() {
        assertEquals(
            mapOf(
                "lseend_slot_6" to "speaker_0",
                "lseend_slot_2" to "speaker_1",
                "lseend_slot_7" to "speaker_2"
            ),
            SpeakerLabelNormalizer.mappingByFirstAppearance(
                listOf("lseend_slot_6", "lseend_slot_2", "lseend_slot_6", "lseend_slot_7")
            )
        )
    }
}
