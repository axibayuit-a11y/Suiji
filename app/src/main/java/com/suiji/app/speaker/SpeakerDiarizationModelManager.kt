package com.suiji.app.speaker

import android.content.Context
import android.os.StatFs
import com.suiji.app.model.LocalModelOperation
import com.suiji.app.model.SpeakerDiarizationModelDescriptor
import com.suiji.app.model.SpeakerDiarizationModelId
import com.suiji.app.model.SpeakerDiarizationModelState
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

/**
 * 只负责说话人分离模型的下载、校验和存储。
 * 它不引用语音识别模型，也不会执行文字转录。
 */
class SpeakerDiarizationModelManager(private val context: Context) {
    private val modelsDirectory = File(context.filesDir, "model_modules/speaker_diarization")
    private val downloadsDirectory = File(context.cacheDir, "model_downloads/speaker_diarization")

    init {
        migrateLegacyModel()
    }

    fun currentStates(): List<SpeakerDiarizationModelState> = catalog.map { descriptor ->
        val installed = isInstalled(descriptor)
        val downloaded = segmentationPart(descriptor.id).length() + embeddingPart(descriptor.id).length()
        SpeakerDiarizationModelState(
            descriptor = descriptor,
            operation = if (installed) LocalModelOperation.INSTALLED else LocalModelOperation.NOT_INSTALLED,
            downloadedBytes = if (installed) descriptor.downloadBytes else downloaded
        )
    }

    fun descriptor(id: SpeakerDiarizationModelId): SpeakerDiarizationModelDescriptor =
        catalog.first { it.id == id }

    fun modelDirectory(id: SpeakerDiarizationModelId): File =
        File(modelsDirectory, id.name.lowercase())

    fun isInstalled(descriptor: SpeakerDiarizationModelDescriptor): Boolean {
        val directory = modelDirectory(descriptor.id)
        return File(directory, READY_MARKER).readTextOrEmpty() == descriptor.version &&
            File(directory, SEGMENTATION_FILENAME).length() > 1_000_000L &&
            File(directory, EMBEDDING_FILENAME).length() > 10_000_000L
    }

    suspend fun downloadAndInstall(
        descriptor: SpeakerDiarizationModelDescriptor,
        onState: (SpeakerDiarizationModelState) -> Unit
    ) = withContext(Dispatchers.IO) {
        modelsDirectory.mkdirs()
        downloadsDirectory.mkdirs()
        val segmentation = segmentationPart(descriptor.id)
        val embedding = embeddingPart(descriptor.id)
        val staging = File(modelsDirectory, ".staging-${descriptor.id.name.lowercase()}")
        try {
            ensureStorageAvailable(descriptor, segmentation.length() + embedding.length())
            downloadFile(
                descriptor,
                descriptor.segmentationDownloadUrl,
                segmentation,
                SEGMENTATION_ARCHIVE_BYTES,
                0L,
                onState
            )
            downloadFile(
                descriptor,
                descriptor.embeddingDownloadUrl,
                embedding,
                EMBEDDING_BYTES,
                SEGMENTATION_ARCHIVE_BYTES,
                onState
            )
            onState(
                SpeakerDiarizationModelState(
                    descriptor,
                    LocalModelOperation.VERIFYING,
                    descriptor.downloadBytes,
                    descriptor.downloadBytes
                )
            )
            staging.deleteRecursively()
            staging.mkdirs()
            extractSegmentation(segmentation, staging)
            embedding.copyTo(File(staging, EMBEDDING_FILENAME), overwrite = true)
            require(File(staging, SEGMENTATION_FILENAME).length() > 1_000_000L)
            require(File(staging, EMBEDDING_FILENAME).length() == EMBEDDING_BYTES)
            File(staging, READY_MARKER).writeText(descriptor.version)

            val target = modelDirectory(descriptor.id)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not activate speaker diarization model" }
            segmentation.delete()
            embedding.delete()
            onState(
                SpeakerDiarizationModelState(
                    descriptor,
                    LocalModelOperation.INSTALLED,
                    descriptor.downloadBytes,
                    descriptor.downloadBytes
                )
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (error is CancellationException) throw error
            onState(
                SpeakerDiarizationModelState(
                    descriptor,
                    LocalModelOperation.FAILED,
                    segmentation.length() + embedding.length(),
                    descriptor.downloadBytes,
                    error.message ?: "Speaker diarization model installation failed"
                )
            )
            throw error
        }
    }

    suspend fun deleteModel(id: SpeakerDiarizationModelId) = withContext(Dispatchers.IO) {
        modelDirectory(id).deleteRecursively()
        segmentationPart(id).delete()
        embeddingPart(id).delete()
    }

    private suspend fun downloadFile(
        descriptor: SpeakerDiarizationModelDescriptor,
        url: String,
        target: File,
        expectedBytes: Long,
        completedBefore: Long,
        onState: (SpeakerDiarizationModelState) -> Unit
    ) {
        if (target.length() == expectedBytes) return
        var existingBytes = target.length().takeIf { it in 1 until expectedBytes } ?: 0L
        if (existingBytes == 0L && target.exists()) target.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Suiji-Android/0.6")
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
                                SpeakerDiarizationModelState(
                                    descriptor,
                                    LocalModelOperation.DOWNLOADING,
                                    completedBefore + downloaded,
                                    descriptor.downloadBytes
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

    private fun extractSegmentation(archive: File, destination: File) {
        var found = false
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile || !tar.canReadEntryData(entry)) continue
                if (entry.name.replace('\\', '/').substringAfterLast('/') != "model.onnx") continue
                BufferedOutputStream(
                    FileOutputStream(File(destination, SEGMENTATION_FILENAME))
                ).use { output -> tar.copyTo(output) }
                found = true
                break
            }
        }
        require(found) { "Speaker segmentation package is missing model.onnx" }
    }

    private fun ensureStorageAvailable(
        descriptor: SpeakerDiarizationModelDescriptor,
        existingBytes: Long
    ) {
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        val required = descriptor.downloadBytes - existingBytes +
            descriptor.installedBytes + SAFETY_BYTES
        require(available > required) { "Not enough free storage for this model" }
    }

    private fun segmentationPart(id: SpeakerDiarizationModelId): File =
        File(downloadsDirectory, "${id.name.lowercase()}-segmentation.part")

    private fun embeddingPart(id: SpeakerDiarizationModelId): File =
        File(downloadsDirectory, "${id.name.lowercase()}-embedding.part")

    private fun migrateLegacyModel() {
        val legacy = File(context.filesDir, "local_models/speaker_diarization")
        val target = modelDirectory(SpeakerDiarizationModelId.PYANNOTE_3D_SPEAKER)
        if (legacy.isDirectory && !target.exists()) {
            target.parentFile?.mkdirs()
            legacy.renameTo(target)
        }
    }

    private fun File.readTextOrEmpty(): String =
        if (isFile) runCatching { readText() }.getOrDefault("") else ""

    companion object {
        const val SEGMENTATION_FILENAME = "segmentation.onnx"
        const val EMBEDDING_FILENAME = "embedding.onnx"
        private const val READY_MARKER = ".ready"
        private const val SEGMENTATION_ARCHIVE_BYTES = 6_958_444L
        private const val EMBEDDING_BYTES = 39_593_761L
        private const val SAFETY_BYTES = 50_000_000L

        val catalog = listOf(
            SpeakerDiarizationModelDescriptor(
                id = SpeakerDiarizationModelId.PYANNOTE_3D_SPEAKER,
                displayName = "Pyannote + 3D-Speaker",
                version = "sherpa-onnx-1.13.4",
                segmentationDownloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
                embeddingDownloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                downloadBytes = SEGMENTATION_ARCHIVE_BYTES + EMBEDDING_BYTES,
                installedBytes = 46_000_000L
            )
        )
    }
}
