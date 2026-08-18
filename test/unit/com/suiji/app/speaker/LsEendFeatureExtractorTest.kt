package com.suiji.app.speaker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsEendFeatureExtractorTest {
    @Test
    fun matchesOfficialLibrosaFrontendForStreamingAudio() {
        val extractor = LsEendFeatureExtractor()
        val audio = FloatArray(16_000) { index ->
            val time = index.toDouble() / 8_000.0
            (0.4 * sin(2.0 * PI * 220.0 * time) +
                0.2 * sin(2.0 * PI * 731.0 * time)).toFloat()
        }
        val features = mutableListOf<FloatArray>()
        audio.asList().chunked(LsEendFeatureExtractor.AUDIO_STEP_SAMPLES).forEach { chunk ->
            features += extractor.accept(chunk.toFloatArray())
        }

        val first = features.first()
        assertEquals(LsEendFeatureExtractor.FEATURE_DIM, first.size)
        assertTrue(first.take(7 * 23).all { it == 0f })
        assertEquals(-1.0199871f, first[200], 0.02f)
        assertEquals(-2.9516659f, first[220], 0.4f)
        assertEquals(-2.4934616f, first[250], 0.4f)
        assertEquals(0.05463029f, first[300], 0.02f)
        assertEquals(-1.2122326f, first[344], 0.4f)
        assertEquals(-175.91197f, first.sum(), 4f)
        assertEquals(180.99115f, first.sumOf { abs(it).toDouble() }.toFloat(), 4f)
    }
}
