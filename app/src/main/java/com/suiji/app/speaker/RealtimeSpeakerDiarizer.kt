package com.suiji.app.speaker

import com.suiji.app.model.LsEendRuntimeProfile
import java.util.ArrayDeque

data class SpeakerActivityFrame(
    val startMs: Long,
    val endMs: Long,
    val probabilities: FloatArray
)

/** One stateful LS-EEND session. Create once when recording starts and close when it stops. */
class RealtimeSpeakerDiarizer(installedModel: InstalledLsEendModel) : AutoCloseable {
    private data class TimedFeature(val startMs: Long, val values: FloatArray)

    private val frontend = when (installedModel.descriptor.runtimeProfile) {
        LsEendRuntimeProfile.STREAMING_1_8_V1 -> LsEendFeatureExtractor()
    }
    private val model = LsEendStreamingModel(installedModel)
    private val features = ArrayDeque<TimedFeature>()
    private val modelTimeline = ArrayDeque<Long>()
    private var nextFeatureStartMs = 0L

    fun accept(samples8Khz: FloatArray): List<SpeakerActivityFrame> {
        frontend.accept(samples8Khz).forEach { values ->
            features.addLast(TimedFeature(nextFeatureStartMs, values))
            nextFeatureStartMs += FRAME_DURATION_MS
        }
        val output = mutableListOf<SpeakerActivityFrame>()
        while (features.size >= model.chunkSize) {
            val chunk = List(model.chunkSize) { features.removeFirst() }
            chunk.forEach { modelTimeline.addLast(it.startMs) }
            val probabilities = model.infer(chunk.map(TimedFeature::values))
            probabilities.forEach { row ->
                // Warm-up rows are explicitly zeroed by the exported streaming graph.
                if (row.all { it == 0f }) return@forEach
                val timestamp = modelTimeline.removeFirst()
                output += SpeakerActivityFrame(
                    startMs = timestamp,
                    endMs = timestamp + FRAME_DURATION_MS,
                    probabilities = row
                )
            }
        }
        return output
    }

    override fun close() {
        model.close()
    }

    private companion object {
        const val FRAME_DURATION_MS = 100L
    }
}
