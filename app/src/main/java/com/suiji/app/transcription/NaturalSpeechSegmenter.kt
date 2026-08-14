package com.suiji.app.transcription

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

data class NaturalSpeechSegment(
    val startMs: Long,
    val endMs: Long,
    val samples: FloatArray
)

/**
 * Uses neural VAD to close a visible transcript segment at a natural pause.
 * maxSpeechDuration is only an inference safety boundary; adjacent safety chunks
 * are merged by the timeline assembler and are not exposed as fixed timestamps.
 */
class NaturalSpeechSegmenter(context: Context) {
    private val assets = context.applicationContext.assets

    fun openSession(): Session = Session(
        Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_ASSET,
                    threshold = 0.5f,
                    minSilenceDuration = 0.65f,
                    minSpeechDuration = 0.30f,
                    windowSize = 512,
                    maxSpeechDuration = 30.0f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )
    )

    class Session internal constructor(private val vad: Vad) : AutoCloseable {
        fun accept(samples: FloatArray): List<NaturalSpeechSegment> {
            if (samples.isNotEmpty()) vad.acceptWaveform(samples)
            return drain()
        }

        fun flush(): List<NaturalSpeechSegment> {
            vad.flush()
            return drain()
        }

        private fun drain(): List<NaturalSpeechSegment> = buildList {
            while (!vad.empty()) {
                val segment = vad.front()
                val startMs = segment.start * 1000L / SAMPLE_RATE
                add(
                    NaturalSpeechSegment(
                        startMs = startMs,
                        endMs = startMs + segment.samples.size * 1000L / SAMPLE_RATE,
                        samples = segment.samples
                    )
                )
                vad.pop()
            }
        }

        override fun close() {
            vad.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val VAD_ASSET = "silero_vad.onnx"
    }
}
