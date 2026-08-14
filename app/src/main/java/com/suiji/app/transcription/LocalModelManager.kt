package com.suiji.app.transcription

import android.content.Context
import android.os.StatFs
import com.suiji.app.model.LocalModelDescriptor
import com.suiji.app.model.LocalModelId
import com.suiji.app.model.LocalModelKind
import com.suiji.app.model.LocalModelOperation
import com.suiji.app.model.LocalModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class LocalModelManager(context: Context) {
    private val modelsDirectory = File(context.filesDir, "local_models")
    private val downloadsDirectory = File(context.cacheDir, "model_downloads")

    fun currentStates(): List<LocalModelState> = catalog.map { descriptor ->
        val installed = isInstalled(descriptor)
        val partial = partialArchive(descriptor)
        val downloaded = if (descriptor.kind == LocalModelKind.SPEAKER_DIARIZATION) {
            File(downloadsDirectory, "${descriptor.id.name.lowercase()}-segmentation.part").length() +
                File(downloadsDirectory, "${descriptor.id.name.lowercase()}-embedding.part").length()
        } else {
            partial.length()
        }
        LocalModelState(
            descriptor = descriptor,
            operation = if (installed) {
                LocalModelOperation.INSTALLED
            } else {
                LocalModelOperation.NOT_INSTALLED
            },
            downloadedBytes = if (installed) descriptor.archiveBytes else downloaded
        )
    }

    fun descriptor(id: LocalModelId): LocalModelDescriptor =
        catalog.first { it.id == id }

    fun modelDirectory(id: LocalModelId): File = File(modelsDirectory, id.name.lowercase())

    fun isInstalled(descriptor: LocalModelDescriptor): Boolean {
        val directory = modelDirectory(descriptor.id)
        if (descriptor.kind == LocalModelKind.SPEAKER_DIARIZATION) {
            return File(directory, READY_MARKER).readTextOrEmpty() == descriptor.version &&
                File(directory, DIARIZATION_SEGMENTATION_FILENAME).length() > 1_000_000L &&
                File(directory, DIARIZATION_EMBEDDING_FILENAME).length() > 10_000_000L
        }
        val model = File(directory, MODEL_FILENAME)
        val tokens = File(directory, TOKENS_FILENAME)
        val marker = File(directory, READY_MARKER)
        return marker.readTextOrEmpty() == descriptor.version &&
            model.isFile && model.length() > MIN_MODEL_BYTES &&
            tokens.isFile && tokens.length() > MIN_TOKENS_BYTES
    }

    suspend fun downloadAndInstall(
        descriptor: LocalModelDescriptor,
        onState: (LocalModelState) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (descriptor.kind == LocalModelKind.SPEAKER_DIARIZATION) {
            downloadAndInstallDiarization(descriptor, onState)
            return@withContext
        }
        modelsDirectory.mkdirs()
        downloadsDirectory.mkdirs()
        val partial = partialArchive(descriptor)
        val staging = File(modelsDirectory, ".staging-${descriptor.id.name.lowercase()}")
        try {
            if (partial.length() != descriptor.archiveBytes) {
                ensureStorageAvailable(descriptor, partial.length())
                downloadArchive(descriptor, partial, onState)
            }
            require(partial.length() == descriptor.archiveBytes) {
                "Downloaded package size does not match the catalog"
            }
            onState(
                LocalModelState(
                    descriptor,
                    LocalModelOperation.VERIFYING,
                    descriptor.archiveBytes,
                    descriptor.archiveBytes
                )
            )
            staging.deleteRecursively()
            staging.mkdirs()
            extractRequiredFiles(partial, staging)
            validateExtractedFiles(staging)
            File(staging, READY_MARKER).writeText(descriptor.version)

            val finalDirectory = modelDirectory(descriptor.id)
            finalDirectory.deleteRecursively()
            check(staging.renameTo(finalDirectory)) { "Could not activate the local model" }
            partial.delete()
            onState(
                LocalModelState(
                    descriptor,
                    LocalModelOperation.INSTALLED,
                    descriptor.archiveBytes,
                    descriptor.archiveBytes
                )
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (error is CancellationException) throw error
            if (partial.length() == descriptor.archiveBytes) partial.delete()
            onState(
                LocalModelState(
                    descriptor,
                    LocalModelOperation.FAILED,
                    partial.length(),
                    descriptor.archiveBytes,
                    error.message ?: "Model installation failed"
                )
            )
            throw error
        }
    }

    suspend fun deleteModel(id: LocalModelId) = withContext(Dispatchers.IO) {
        modelDirectory(id).deleteRecursively()
        partialArchive(descriptor(id)).delete()
        File(downloadsDirectory, "${id.name.lowercase()}-segmentation.part").delete()
        File(downloadsDirectory, "${id.name.lowercase()}-embedding.part").delete()
    }

    private suspend fun downloadAndInstallDiarization(
        descriptor: LocalModelDescriptor,
        onState: (LocalModelState) -> Unit
    ) {
        modelsDirectory.mkdirs()
        downloadsDirectory.mkdirs()
        val secondaryUrl = requireNotNull(descriptor.secondaryDownloadUrl)
        val segmentationPart = File(
            downloadsDirectory,
            "${descriptor.id.name.lowercase()}-segmentation.part"
        )
        val embeddingPart = File(
            downloadsDirectory,
            "${descriptor.id.name.lowercase()}-embedding.part"
        )
        val staging = File(modelsDirectory, ".staging-${descriptor.id.name.lowercase()}")
        try {
            ensureStorageAvailable(descriptor, segmentationPart.length() + embeddingPart.length())
            downloadDiarizationFile(
                descriptor = descriptor,
                url = descriptor.downloadUrl,
                target = segmentationPart,
                expectedBytes = DIARIZATION_SEGMENTATION_ARCHIVE_BYTES,
                completedBefore = 0L,
                onState = onState
            )
            downloadDiarizationFile(
                descriptor = descriptor,
                url = secondaryUrl,
                target = embeddingPart,
                expectedBytes = DIARIZATION_EMBEDDING_BYTES,
                completedBefore = DIARIZATION_SEGMENTATION_ARCHIVE_BYTES,
                onState = onState
            )
            onState(
                LocalModelState(
                    descriptor,
                    LocalModelOperation.VERIFYING,
                    descriptor.archiveBytes,
                    descriptor.archiveBytes
                )
            )
            staging.deleteRecursively()
            staging.mkdirs()
            extractDiarizationSegmentation(segmentationPart, staging)
            embeddingPart.copyTo(
                File(staging, DIARIZATION_EMBEDDING_FILENAME),
                overwrite = true
            )
            require(File(staging, DIARIZATION_SEGMENTATION_FILENAME).length() > 1_000_000L) {
                "Speaker segmentation model is incomplete"
            }
            require(File(staging, DIARIZATION_EMBEDDING_FILENAME).length() == DIARIZATION_EMBEDDING_BYTES) {
                "Speaker embedding model is incomplete"
            }
            File(staging, READY_MARKER).writeText(descriptor.version)
            val finalDirectory = modelDirectory(descriptor.id)
            finalDirectory.deleteRecursively()
            check(staging.renameTo(finalDirectory)) { "Could not activate speaker diarization" }
            segmentationPart.delete()
            embeddingPart.delete()
            onState(
                LocalModelState(
                    descriptor,
                    LocalModelOperation.INSTALLED,
                    descriptor.archiveBytes,
                    descriptor.archiveBytes
                )
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (error is CancellationException) throw error
            onState(
                LocalModelState(
                    descriptor,
                    LocalModelOperation.FAILED,
                    segmentationPart.length() + embeddingPart.length(),
                    descriptor.archiveBytes,
                    error.message ?: "Speaker diarization installation failed"
                )
            )
            throw error
        }
    }

    private suspend fun downloadDiarizationFile(
        descriptor: LocalModelDescriptor,
        url: String,
        target: File,
        expectedBytes: Long,
        completedBefore: Long,
        onState: (LocalModelState) -> Unit
    ) {
        if (target.length() == expectedBytes) return
        var existingBytes = target.length().takeIf { it in 1 until expectedBytes } ?: 0L
        if (existingBytes == 0L && target.exists()) target.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Suiji-Android/0.5")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) existingBytes = 0L
            require(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Model download failed: HTTP $responseCode"
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(target, append)).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existingBytes
                    var lastReported = existingBytes
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= 512 * 1024) {
                            lastReported = downloaded
                            onState(
                                LocalModelState(
                                    descriptor,
                                    LocalModelOperation.DOWNLOADING,
                                    completedBefore + downloaded,
                                    descriptor.archiveBytes
                                )
                            )
                        }
                    }
                }
            }
            require(target.length() == expectedBytes) { "Downloaded model file is incomplete" }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractDiarizationSegmentation(archive: File, destination: File) {
        var found = false
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile || !tar.canReadEntryData(entry)) continue
                val leafName = entry.name.replace('\\', '/').substringAfterLast('/')
                if (leafName != "model.onnx") continue
                BufferedOutputStream(
                    FileOutputStream(File(destination, DIARIZATION_SEGMENTATION_FILENAME))
                ).use { output -> tar.copyTo(output) }
                found = true
                break
            }
        }
        require(found) { "Speaker segmentation package is missing model.onnx" }
    }

    private suspend fun downloadArchive(
        descriptor: LocalModelDescriptor,
        partial: File,
        onState: (LocalModelState) -> Unit
    ) {
        var existingBytes = partial.length().takeIf { it in 1 until descriptor.archiveBytes } ?: 0L
        if (existingBytes == 0L && partial.exists()) partial.delete()
        val connection = (URL(descriptor.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Suiji-Android/0.4")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }

        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) existingBytes = 0L
            require(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Model download failed: HTTP $responseCode"
            }
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
                        if (downloaded - lastReported >= 512 * 1024) {
                            lastReported = downloaded
                            onState(
                                LocalModelState(
                                    descriptor,
                                    LocalModelOperation.DOWNLOADING,
                                    downloaded,
                                    descriptor.archiveBytes
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

    private fun extractRequiredFiles(archive: File, destination: File) {
        var foundModel = false
        var foundTokens = false
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile || !tar.canReadEntryData(entry)) continue
                val leafName = entry.name.replace('\\', '/').substringAfterLast('/')
                val targetName = when (leafName) {
                    MODEL_FILENAME -> MODEL_FILENAME.also { foundModel = true }
                    TOKENS_FILENAME -> TOKENS_FILENAME.also { foundTokens = true }
                    else -> null
                } ?: continue
                val target = File(destination, targetName)
                require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    "Unsafe path in model package"
                }
                BufferedOutputStream(FileOutputStream(target)).use { output -> tar.copyTo(output) }
            }
        }
        require(foundModel && foundTokens) { "Model package is missing required files" }
    }

    private fun validateExtractedFiles(directory: File) {
        require(File(directory, MODEL_FILENAME).length() > MIN_MODEL_BYTES) {
            "The model file is incomplete"
        }
        require(File(directory, TOKENS_FILENAME).length() > MIN_TOKENS_BYTES) {
            "The token file is incomplete"
        }
    }

    private fun ensureStorageAvailable(descriptor: LocalModelDescriptor, existingBytes: Long) {
        val available = StatFs(modelsDirectory.parentFile!!.absolutePath).availableBytes
        val required = (descriptor.archiveBytes - existingBytes) + descriptor.installedBytes + SAFETY_BYTES
        require(available > required) { "Not enough free storage for this model" }
    }

    private fun partialArchive(descriptor: LocalModelDescriptor): File =
        File(downloadsDirectory, "${descriptor.id.name.lowercase()}.tar.bz2.part")

    private fun File.readTextOrEmpty(): String =
        if (isFile) runCatching { readText() }.getOrDefault("") else ""

    companion object {
        private const val MODEL_FILENAME = "model.int8.onnx"
        private const val TOKENS_FILENAME = "tokens.txt"
        private const val READY_MARKER = ".ready"
        private const val MIN_MODEL_BYTES = 200_000_000L
        private const val MIN_TOKENS_BYTES = 100_000L
        private const val SAFETY_BYTES = 100_000_000L
        private const val DIARIZATION_SEGMENTATION_FILENAME = "segmentation.onnx"
        private const val DIARIZATION_EMBEDDING_FILENAME = "embedding.onnx"
        private const val DIARIZATION_SEGMENTATION_ARCHIVE_BYTES = 6_958_444L
        private const val DIARIZATION_EMBEDDING_BYTES = 39_593_761L

        val catalog = listOf(
            LocalModelDescriptor(
                id = LocalModelId.SENSEVOICE_GENERAL,
                displayName = "SenseVoice Small Int8",
                version = "2024-07-17",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
                archiveBytes = 163_002_883L,
                installedBytes = 239_000_000L,
                useInverseTextNormalization = true
            ),
            LocalModelDescriptor(
                id = LocalModelId.SENSEVOICE_CANTONESE,
                displayName = "SenseVoice Cantonese Int8",
                version = "2025-09-09",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
                archiveBytes = 165_783_878L,
                installedBytes = 237_000_000L,
                useInverseTextNormalization = false
            ),
            LocalModelDescriptor(
                id = LocalModelId.SPEAKER_DIARIZATION,
                displayName = "说话人分离 · Pyannote + 3D-Speaker",
                version = "sherpa-onnx-1.13.4",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
                archiveBytes = DIARIZATION_SEGMENTATION_ARCHIVE_BYTES + DIARIZATION_EMBEDDING_BYTES,
                installedBytes = 46_000_000L,
                useInverseTextNormalization = false,
                kind = LocalModelKind.SPEAKER_DIARIZATION,
                secondaryDownloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
            )
        )
    }
}
