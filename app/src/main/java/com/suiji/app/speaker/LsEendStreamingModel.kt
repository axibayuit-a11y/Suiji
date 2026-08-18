package com.suiji.app.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest

internal class LsEendStreamingModel(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var encKv = FloatArray(4 * 1 * 4 * 64 * 64)
    private var encScale = FloatArray(4 * 4)
    private var encConvCache = FloatArray(4 * 1 * 256 * 15)
    private var cnnWindow = FloatArray(1 * 256 * 18)
    private var cnnCount = FloatArray(1)
    private var decKv = FloatArray(2 * 10 * 4 * 64 * 64)
    private var decScale = FloatArray(2 * 4)

    init {
        val modelFile = installBundledModel(context.applicationContext)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = try {
            environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    fun infer(features: List<FloatArray>): List<FloatArray> {
        require(features.size == CHUNK_SIZE)
        val flatFeatures = FloatArray(CHUNK_SIZE * LsEendFeatureExtractor.FEATURE_DIM)
        features.forEachIndexed { index, feature ->
            require(feature.size == LsEendFeatureExtractor.FEATURE_DIM)
            feature.copyInto(flatFeatures, index * feature.size)
        }

        val tensors = linkedMapOf(
            "features" to tensor(
                flatFeatures,
                1,
                CHUNK_SIZE.toLong(),
                LsEendFeatureExtractor.FEATURE_DIM.toLong()
            ),
            "enc_kv" to tensor(encKv, 4, 1, 4, 64, 64),
            "enc_scale" to tensor(encScale, 4, 4),
            "enc_conv_cache" to tensor(encConvCache, 4, 1, 256, 15),
            "cnn_window" to tensor(cnnWindow, 1, 256, 18),
            "cnn_count" to tensor(cnnCount, 1),
            "dec_kv" to tensor(decKv, 2, 10, 4, 64, 64),
            "dec_scale" to tensor(decScale, 2, 4)
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
                return List(CHUNK_SIZE) { row ->
                    probabilities.copyOfRange(
                        row * MAX_SPEAKERS,
                        (row + 1) * MAX_SPEAKERS
                    )
                }
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    private fun tensor(values: FloatArray, vararg shape: Long): OnnxTensor =
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

    private fun installBundledModel(context: Context): File {
        val directory = File(context.filesDir, "models/lseend").apply { mkdirs() }
        val target = File(directory, MODEL_FILENAME)
        if (target.length() == MODEL_BYTES && sha256(target) == MODEL_SHA256) return target

        val staging = File(directory, "$MODEL_FILENAME.part")
        context.assets.open("models/$MODEL_FILENAME").use { input ->
            FileOutputStream(staging, false).use(input::copyTo)
        }
        require(staging.length() == MODEL_BYTES && sha256(staging) == MODEL_SHA256) {
            "The bundled LS-EEND model failed integrity validation"
        }
        if (target.exists()) check(target.delete())
        check(staging.renameTo(target)) { "Could not install the bundled LS-EEND model" }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CHUNK_SIZE = 5
        const val MAX_SPEAKERS = 8
        private const val MODEL_FILENAME = "lseend-streaming-1-8spk.onnx"
        private const val MODEL_BYTES = 44_947_938L
        private const val MODEL_SHA256 =
            "c4f104d9426518be1c6bf9f1f27b1801906e194ce9cc7f62305412da6f4f01b8"
    }
}
