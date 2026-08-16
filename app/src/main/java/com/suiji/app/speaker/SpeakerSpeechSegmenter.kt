package com.suiji.app.speaker

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

data class SpeakerSpeechSegment(
    val startMs: Long,
    val endMs: Long,
    val samples: FloatArray
)

/**
 * Thin Android wrapper around sherpa-onnx Silero VAD. Parameters follow the
 * official dynamic speaker-identification example: speech is filtered before
 * embedding extraction and long speech is bounded into stable enrollment units.
 */
class SpeakerSpeechSegmenter(context: Context) {
    private val assets = context.applicationContext.assets

    fun openSession(): Session = Session(
        Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_ASSET,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.50f,
                    windowSize = 512,
                    maxSpeechDuration = 1.80f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )
    )

    class Session internal constructor(private val vad: Vad) : AutoCloseable {
        fun accept(samples: FloatArray): List<SpeakerSpeechSegment> {
            if (samples.isNotEmpty()) vad.acceptWaveform(samples)
            return drain()
        }

        fun flush(): List<SpeakerSpeechSegment> {
            vad.flush()
            return drain()
        }

        private fun drain(): List<SpeakerSpeechSegment> = buildList {
            while (!vad.empty()) {
                val segment = vad.front()
                val startMs = segment.start * 1000L / SAMPLE_RATE
                add(
                    SpeakerSpeechSegment(
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
