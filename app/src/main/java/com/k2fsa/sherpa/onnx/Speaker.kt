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
    fun dim(): Int = dim(ptr)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: SpeakerEmbeddingExtractorConfig
    ): Long
    private external fun newFromFile(config: SpeakerEmbeddingExtractorConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun compute(ptr: Long, streamPtr: Long): FloatArray
    private external fun dim(ptr: Long): Int

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

/**
 * Official sherpa-onnx speaker enrollment/search wrapper (v1.13.4).
 * Similarity calculation and enrolled-speaker matching stay in the native
 * library rather than being reimplemented by the app.
 */
class SpeakerEmbeddingManager(val dim: Int) {
    private var ptr: Long = create(dim)

    init {
        require(ptr != 0L) { "Could not initialize the speaker embedding manager" }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun release() = finalize()
    fun add(name: String, embedding: FloatArray): Boolean = add(ptr, name, embedding)
    fun add(name: String, embeddings: Array<FloatArray>): Boolean =
        addList(ptr, name, embeddings)
    fun remove(name: String): Boolean = remove(ptr, name)
    fun search(embedding: FloatArray, threshold: Float): String =
        search(ptr, embedding, threshold)
    fun verify(name: String, embedding: FloatArray, threshold: Float): Boolean =
        verify(ptr, name, embedding, threshold)
    fun contains(name: String): Boolean = contains(ptr, name)
    fun numSpeakers(): Int = numSpeakers(ptr)
    fun allSpeakerNames(): Array<String> = allSpeakerNames(ptr)

    private external fun create(dim: Int): Long
    private external fun delete(ptr: Long)
    private external fun add(ptr: Long, name: String, embedding: FloatArray): Boolean
    private external fun addList(
        ptr: Long,
        name: String,
        embeddings: Array<FloatArray>
    ): Boolean
    private external fun remove(ptr: Long, name: String): Boolean
    private external fun search(ptr: Long, embedding: FloatArray, threshold: Float): String
    private external fun verify(
        ptr: Long,
        name: String,
        embedding: FloatArray,
        threshold: Float
    ): Boolean
    private external fun contains(ptr: Long, name: String): Boolean
    private external fun numSpeakers(ptr: Long): Int
    private external fun allSpeakerNames(ptr: Long): Array<String>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
