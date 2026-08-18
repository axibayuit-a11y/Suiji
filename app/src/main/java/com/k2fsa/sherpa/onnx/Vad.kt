// Copyright (c) 2023 Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0f
)

data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0f
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16_000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false
)

class SpeechSegment(val start: Int, val samples: FloatArray)

class Vad(
    assetManager: AssetManager? = null,
    config: VadModelConfig
) {
    private var ptr: Long = if (assetManager != null) {
        newFromAsset(assetManager, config)
    } else {
        newFromFile(config)
    }

    init {
        require(ptr != 0L) { "Invalid VAD configuration" }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun release() = finalize()
    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)
    fun empty(): Boolean = empty(ptr)
    fun pop() = pop(ptr)
    fun front(): SpeechSegment = front(ptr)
    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(assetManager: AssetManager, config: VadModelConfig): Long
    private external fun newFromFile(config: VadModelConfig): Long
    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
