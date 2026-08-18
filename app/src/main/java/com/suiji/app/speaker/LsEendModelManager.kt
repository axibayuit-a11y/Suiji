package com.suiji.app.speaker

import android.content.Context
import android.os.StatFs
import com.suiji.app.model.LsEendModelDescriptor
import com.suiji.app.model.LsEendModelId
import com.suiji.app.model.LsEendModelState
import com.suiji.app.model.LsEendRuntimeProfile
import com.suiji.app.model.ModelOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class InstalledLsEendModel(
    val descriptor: LsEendModelDescriptor,
    val file: File
)

/** Downloads and activates LS-EEND model files; it never selects a fallback model. */
class LsEendModelManager(context: Context) {
    private val modelsDirectory = File(context.filesDir, "model_modules/speaker_diarization")
    private val downloadsDirectory = File(context.cacheDir, "model_downloads/speaker_diarization")

    fun currentStates(): List<LsEendModelState> = catalog.map { descriptor ->
        val installed = isInstalled(descriptor)
        val partialBytes = partialModel(descriptor).length().coerceAtMost(descriptor.modelBytes)
        LsEendModelState(
            descriptor = descriptor,
            operation = if (installed) ModelOperation.INSTALLED else ModelOperation.NOT_INSTALLED,
            downloadedBytes = if (installed) descriptor.modelBytes else partialBytes
        )
    }

    fun descriptor(id: LsEendModelId): LsEendModelDescriptor =
        catalog.first { it.id == id }

    fun isInstalled(descriptor: LsEendModelDescriptor): Boolean {
        val directory = modelDirectory(descriptor.id)
        return File(directory, READY_MARKER).readTextOrEmpty() == marker(descriptor) &&
            File(directory, MODEL_FILENAME).length() == descriptor.modelBytes
    }

    fun requireInstalled(id: LsEendModelId): InstalledLsEendModel {
        val descriptor = descriptor(id)
        check(isInstalled(descriptor)) { "The selected LS-EEND model is not installed" }
        return InstalledLsEendModel(descriptor, File(modelDirectory(id), MODEL_FILENAME))
    }

    suspend fun downloadAndInstall(
        descriptor: LsEendModelDescriptor,
        onState: (LsEendModelState) -> Unit
    ) = withContext(Dispatchers.IO) {
        modelsDirectory.mkdirs()
        downloadsDirectory.mkdirs()
        val partial = partialModel(descriptor)
        val staging = File(modelsDirectory, ".staging-${descriptor.id.name.lowercase()}")
        try {
            if (partial.length() != descriptor.modelBytes) {
                ensureStorageAvailable(descriptor, partial.length())
                downloadModel(descriptor, partial, onState)
            }
            require(partial.length() == descriptor.modelBytes) {
                "Downloaded model size does not match the catalog"
            }
            onState(
                LsEendModelState(
                    descriptor,
                    ModelOperation.VERIFYING,
                    descriptor.modelBytes,
                    descriptor.modelBytes
                )
            )
            require(sha256(partial) == descriptor.sha256) {
                partial.delete()
                "Downloaded model checksum does not match the catalog"
            }

            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not create the model staging directory" }
            partial.copyTo(File(staging, MODEL_FILENAME), overwrite = true)
            File(staging, READY_MARKER).writeText(marker(descriptor))

            val target = modelDirectory(descriptor.id)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not activate the LS-EEND model" }
            partial.delete()
            onState(
                LsEendModelState(
                    descriptor,
                    ModelOperation.INSTALLED,
                    descriptor.modelBytes,
                    descriptor.modelBytes
                )
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (error is CancellationException) throw error
            onState(
                LsEendModelState(
                    descriptor,
                    ModelOperation.FAILED,
                    partial.length().coerceAtMost(descriptor.modelBytes),
                    descriptor.modelBytes,
                    error.message ?: "LS-EEND model installation failed"
                )
            )
            throw error
        }
    }

    suspend fun deleteModel(id: LsEendModelId) = withContext(Dispatchers.IO) {
        modelDirectory(id).deleteRecursively()
        partialModel(descriptor(id)).delete()
    }

    private suspend fun downloadModel(
        descriptor: LsEendModelDescriptor,
        partial: File,
        onState: (LsEendModelState) -> Unit
    ) {
        var existingBytes = partial.length().takeIf { it in 1 until descriptor.modelBytes } ?: 0L
        if (existingBytes == 0L && partial.exists()) partial.delete()
        val connection = (URL(descriptor.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Suiji-Android/LS-EEND")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }

        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                existingBytes = 0L
                partial.delete()
            }
            require(
                responseCode == HttpURLConnection.HTTP_OK ||
                    responseCode == HttpURLConnection.HTTP_PARTIAL
            ) { "Model download failed: HTTP $responseCode" }

            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(partial, append)).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existingBytes
                    var lastReported = existingBytes
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        require(downloaded <= descriptor.modelBytes) {
                            "Downloaded model is larger than the catalog entry"
                        }
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = downloaded
                            onState(
                                LsEendModelState(
                                    descriptor,
                                    ModelOperation.DOWNLOADING,
                                    downloaded,
                                    descriptor.modelBytes
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureStorageAvailable(descriptor: LsEendModelDescriptor, existingBytes: Long) {
        val storageRoot = modelsDirectory.parentFile ?: modelsDirectory
        storageRoot.mkdirs()
        val available = StatFs(storageRoot.absolutePath).availableBytes
        val required = descriptor.modelBytes - existingBytes + descriptor.modelBytes + SAFETY_BYTES
        require(available > required) { "Not enough free storage for this model" }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun modelDirectory(id: LsEendModelId): File =
        File(modelsDirectory, id.name.lowercase())

    private fun partialModel(descriptor: LsEendModelDescriptor): File =
        File(downloadsDirectory, "${descriptor.id.name.lowercase()}-${descriptor.version}.onnx.part")

    private fun marker(descriptor: LsEendModelDescriptor): String =
        "${descriptor.version}\n${descriptor.sha256}"

    private fun File.readTextOrEmpty(): String =
        if (isFile) runCatching { readText() }.getOrDefault("") else ""

    companion object {
        private const val MODEL_FILENAME = "model.onnx"
        private const val READY_MARKER = ".ready"
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
        private const val SAFETY_BYTES = 50 * 1024 * 1024L

        val catalog = listOf(
            LsEendModelDescriptor(
                id = LsEendModelId.GENERIC_1_8,
                displayName = "LS-EEND General 1–8",
                version = "1.0.0",
                downloadUrl = "https://github.com/axibayuit-a11y/Suiji/releases/" +
                    "download/v0.16.1/lseend-streaming-1-8spk.onnx",
                modelBytes = 44_947_938L,
                sha256 = "c4f104d9426518be1c6bf9f1f27b1801906e194ce9cc7f62305412da6f4f01b8",
                maxSpeakers = 8,
                runtimeProfile = LsEendRuntimeProfile.STREAMING_1_8_V1
            )
        )
    }
}
