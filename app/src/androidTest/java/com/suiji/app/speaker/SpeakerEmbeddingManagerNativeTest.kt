package com.suiji.app.speaker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerEmbeddingManagerNativeTest {
    @Test
    fun officialManagerRegistersAndSearchesSpeakerOnAndroid() {
        val manager = SpeakerEmbeddingManager(dim = 4)
        try {
            assertTrue(manager.add("speaker_0", floatArrayOf(1f, 0f, 0f, 0f)))
            assertEquals(1, manager.numSpeakers())
            assertEquals(
                "speaker_0",
                manager.search(floatArrayOf(0.99f, 0.01f, 0f, 0f), threshold = 0.5f)
            )
        } finally {
            manager.release()
        }
    }
}
