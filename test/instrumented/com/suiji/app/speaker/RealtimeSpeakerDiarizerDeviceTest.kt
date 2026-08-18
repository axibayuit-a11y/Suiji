package com.suiji.app.speaker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeSpeakerDiarizerDeviceTest {
    @Test
    fun bundledModelDetectsFourChangingTracksWithoutResettingState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pcm = instrumentation.context.assets
            .open("lseend_four_speakers_40s.wav")
            .use { input -> input.readBytes() }
        require(pcm.size > WAV_HEADER_BYTES)

        val samples = ByteBuffer.wrap(pcm, WAV_HEADER_BYTES, pcm.size - WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .let { buffer ->
                FloatArray(buffer.remaining()) { buffer.get() / 32768.0f }
            }

        val frames = mutableListOf<SpeakerActivityFrame>()
        RealtimeSpeakerDiarizer(context).use { diarizer ->
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + LsEendFeatureExtractor.AUDIO_STEP_SAMPLES, samples.size)
                frames += diarizer.accept(samples.copyOfRange(offset, end))
                offset = end
            }
        }

        val activeFrameCounts = IntArray(LsEendStreamingModel.MAX_SPEAKERS)
        val activeTracks = buildSet {
            frames.forEach { frame ->
                frame.probabilities.forEachIndexed { index, probability ->
                    if (probability >= 0.5f) {
                        add(index)
                        activeFrameCounts[index] += 1
                    }
                }
            }
        }
        // 400 input steps - one centered-FFT tail step - nine CNN warm-up steps
        // - four incomplete fixed-chunk steps = 386 live outputs.
        assertEquals(386, frames.size)
        assertEquals(setOf(0, 1, 2, 3), activeTracks)
        assertTrue(activeFrameCounts.take(4).all { it >= 65 })
        assertTrue(frames.zipWithNext().all { (first, second) -> first.endMs == second.startMs })
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
