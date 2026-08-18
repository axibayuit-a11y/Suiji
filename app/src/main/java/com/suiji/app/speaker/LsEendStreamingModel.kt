package com.suiji.app.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.suiji.app.model.LsEendRuntimeProfile
import java.nio.FloatBuffer

internal class LsEendStreamingModel(installed: InstalledLsEendModel) : AutoCloseable {
    private val specification = RuntimeSpecification.forProfile(installed.descriptor.runtimeProfile)
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var encKv = zeros(specification.encKvShape)
    private var encScale = zeros(specification.encScaleShape)
    private var encConvCache = zeros(specification.encConvCacheShape)
    private var cnnWindow = zeros(specification.cnnWindowShape)
    private var cnnCount = zeros(specification.cnnCountShape)
    private var decKv = zeros(specification.decKvShape)
    private var decScale = zeros(specification.decScaleShape)

    val chunkSize: Int
        get() = specification.chunkSize

    init {
        require(specification.maxSpeakers == installed.descriptor.maxSpeakers) {
            "The LS-EEND model catalog does not match its runtime profile"
        }
        require(installed.file.isFile && installed.file.length() == installed.descriptor.modelBytes) {
            "The selected LS-EEND model file is unavailable"
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = try {
            environment.createSession(installed.file.absolutePath, options)
        } finally {
            options.close()
        }
    }

    fun infer(features: List<FloatArray>): List<FloatArray> {
        require(features.size == specification.chunkSize)
        val flatFeatures = FloatArray(specification.chunkSize * specification.featureDimension)
        features.forEachIndexed { index, feature ->
            require(feature.size == specification.featureDimension)
            feature.copyInto(flatFeatures, index * feature.size)
        }

        val tensors = linkedMapOf(
            "features" to tensor(
                flatFeatures,
                longArrayOf(1, specification.chunkSize.toLong(), specification.featureDimension.toLong())
            ),
            "enc_kv" to tensor(encKv, specification.encKvShape),
            "enc_scale" to tensor(encScale, specification.encScaleShape),
            "enc_conv_cache" to tensor(encConvCache, specification.encConvCacheShape),
            "cnn_window" to tensor(cnnWindow, specification.cnnWindowShape),
            "cnn_count" to tensor(cnnCount, specification.cnnCountShape),
            "dec_kv" to tensor(decKv, specification.decKvShape),
            "dec_scale" to tensor(decScale, specification.decScaleShape)
        )

        try {
            session.run(tensors).use { result ->
                val probabilities = floats(result, "probabilities")
                encKv = floats(result, "enc_kv_out")
                encScale = floats(result, "enc_scale_out")
                encConvCache = floats(result, "enc_conv_cache_out")
                cnnWindow = floats(result, "cnn_window_out")
                cnnCount = floats(result, "cnn_count_out")
                decKv = floats(result, "dec_kv_out")
                decScale = floats(result, "dec_scale_out")
                return List(specification.chunkSize) { row ->
                    probabilities.copyOfRange(
                        row * specification.maxSpeakers,
                        (row + 1) * specification.maxSpeakers
                    )
                }
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    private fun tensor(values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)

    private fun floats(result: OrtSession.Result, name: String): FloatArray {
        val tensor = result[name].orElseThrow {
            IllegalStateException("LS-EEND output is missing: $name")
        } as OnnxTensor
        val buffer = tensor.floatBuffer
        val output = FloatArray(buffer.remaining())
        buffer.get(output)
        return output
    }

    override fun close() {
        session.close()
    }

    private data class RuntimeSpecification(
        val chunkSize: Int,
        val featureDimension: Int,
        val maxSpeakers: Int,
        val encKvShape: LongArray,
        val encScaleShape: LongArray,
        val encConvCacheShape: LongArray,
        val cnnWindowShape: LongArray,
        val cnnCountShape: LongArray,
        val decKvShape: LongArray,
        val decScaleShape: LongArray
    ) {
        companion object {
            fun forProfile(profile: LsEendRuntimeProfile): RuntimeSpecification = when (profile) {
                LsEendRuntimeProfile.STREAMING_1_8_V1 -> RuntimeSpecification(
                    chunkSize = 5,
                    featureDimension = LsEendFeatureExtractor.FEATURE_DIM,
                    maxSpeakers = 8,
                    encKvShape = longArrayOf(4, 1, 4, 64, 64),
                    encScaleShape = longArrayOf(4, 4),
                    encConvCacheShape = longArrayOf(4, 1, 256, 15),
                    cnnWindowShape = longArrayOf(1, 256, 18),
                    cnnCountShape = longArrayOf(1),
                    decKvShape = longArrayOf(2, 10, 4, 64, 64),
                    decScaleShape = longArrayOf(2, 4)
                )
            }
        }
    }

    private companion object {
        fun zeros(shape: LongArray): FloatArray =
            FloatArray(shape.fold(1L, Long::times).toInt())
    }
}
