package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

class SpeakerEmbeddingExtractor(
    assetManager: AssetManager? = null,
    config: SpeakerEmbeddingExtractorConfig
) {
    private var ptr: Long = if (assetManager != null) {
        newFromAsset(assetManager, config)
    } else {
        newFromFile(config)
    }

    init {
        require(ptr != 0L) { "Could not initialize the speaker embedding model" }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun release() = finalize()
    fun createStream(): OnlineStream = OnlineStream(createStream(ptr))
    fun isReady(stream: OnlineStream): Boolean = isReady(ptr, stream.ptr)
    fun compute(stream: OnlineStream): FloatArray = compute(ptr, stream.ptr)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: SpeakerEmbeddingExtractorConfig
    ): Long
    private external fun newFromFile(config: SpeakerEmbeddingExtractorConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun compute(ptr: Long, streamPtr: Long): FloatArray

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
