package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineSpeakerSegmentationPyannoteModelConfig(
    var model: String = ""
)

data class OfflineSpeakerSegmentationModelConfig(
    var pyannote: OfflineSpeakerSegmentationPyannoteModelConfig =
        OfflineSpeakerSegmentationPyannoteModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu"
)

data class FastClusteringConfig(
    var numClusters: Int = -1,
    var threshold: Float = 0.5f
)

data class OfflineSpeakerDiarizationConfig(
    var segmentation: OfflineSpeakerSegmentationModelConfig =
        OfflineSpeakerSegmentationModelConfig(),
    var embedding: SpeakerEmbeddingExtractorConfig = SpeakerEmbeddingExtractorConfig(),
    var clustering: FastClusteringConfig = FastClusteringConfig(),
    var minDurationOn: Float = 0.2f,
    var minDurationOff: Float = 0.5f
)

data class OfflineSpeakerDiarizationSegment(
    val start: Float,
    val end: Float,
    val speaker: Int
)

class OfflineSpeakerDiarization(
    assetManager: AssetManager? = null,
    val config: OfflineSpeakerDiarizationConfig
) {
    private var ptr: Long = if (assetManager != null) {
        newFromAsset(assetManager, config)
    } else {
        newFromFile(config)
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun release() = finalize()
    fun sampleRate(): Int = getSampleRate(ptr)
    fun process(samples: FloatArray): Array<OfflineSpeakerDiarizationSegment> = process(ptr, samples)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineSpeakerDiarizationConfig
    ): Long
    private external fun newFromFile(config: OfflineSpeakerDiarizationConfig): Long
    private external fun getSampleRate(ptr: Long): Int
    private external fun process(
        ptr: Long,
        samples: FloatArray
    ): Array<OfflineSpeakerDiarizationSegment>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
