package com.suiji.app.transcription

import com.suiji.app.model.CloudTranscriptionConfig
import com.suiji.app.model.TranscriptSegment
import com.suiji.app.model.TranscriptionResult
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class OpenAiCompatibleTranscriptionEngine : TranscriptionEngine {
    override fun transcribe(
        audioFile: File,
        config: CloudTranscriptionConfig
    ): Result<TranscriptionResult> = runCatching {
        require(config.isReady) { "Cloud transcription is not configured" }
        require(audioFile.isFile) { "Audio file is missing" }

        val endpoint = transcriptionEndpoint(config.baseUrl)
        val boundary = "Suiji-${UUID.randomUUID()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 10 * 60_000
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Suiji-Android/0.3")
        }

        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                output.writeTextPart(boundary, "model", config.model)
                if (config.speakerDiarization) {
                    output.writeTextPart(boundary, "response_format", "diarized_json")
                    output.writeTextPart(boundary, "chunking_strategy", "auto")
                }
                output.writeFilePart(
                    boundary,
                    "file",
                    audioFile,
                    if (audioFile.extension.equals("wav", true)) "audio/wav" else "audio/mp4"
                )
                output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val responseBody = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (statusCode !in 200..299) {
                val message = runCatching {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "HTTP $statusCode"
                error(message)
            }

            val transcript = runCatching {
                val json = JSONObject(responseBody)
                if (config.speakerDiarization) {
                    json.toDiarizedResult()
                } else {
                    TranscriptionResult(json.optString("text").trim())
                }
            }.getOrDefault(TranscriptionResult(""))
            val normalized = if (transcript.text.isBlank()) {
                TranscriptionResult(responseBody.takeUnless { it.trimStart().startsWith("{") }.orEmpty().trim())
            } else {
                transcript
            }
            require(normalized.text.isNotBlank()) { "The transcription response did not contain text" }
            normalized
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toDiarizedResult(): TranscriptionResult {
        val segments = optJSONArray("segments")
            ?: return TranscriptionResult(optString("text"))
        val parsedSegments = buildList {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index) ?: continue
                val speaker = segment.optString("speaker", "speaker_${index + 1}")
                val text = segment.optString("text").trim()
                if (text.isNotBlank()) {
                    val startMs = secondsToMs(segment, "start", "start_time")
                    val endMs = secondsToMs(segment, "end", "end_time").coerceAtLeast(startMs)
                    add(TranscriptSegment(startMs, endMs, text, speaker))
                }
            }
        }
        val text = parsedSegments.joinToString("\n") { segment ->
            "${segment.speakerId}: ${segment.text}"
        }.ifBlank { optString("text") }
        return TranscriptionResult(text, parsedSegments)
    }

    private fun secondsToMs(json: JSONObject, primary: String, fallback: String): Long {
        val seconds = when {
            json.has(primary) -> json.optDouble(primary, 0.0)
            json.has(fallback) -> json.optDouble(fallback, 0.0)
            else -> 0.0
        }
        return (seconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    private fun transcriptionEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith("/audio/transcriptions")) {
            normalized
        } else {
            "$normalized/audio/transcriptions"
        }
    }

    private fun BufferedOutputStream.writeTextPart(
        boundary: String,
        name: String,
        value: String
    ) {
        write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        write(value.toByteArray(Charsets.UTF_8))
        write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun BufferedOutputStream.writeFilePart(
        boundary: String,
        name: String,
        file: File,
        contentType: String
    ) {
        val safeName = file.name.replace("\"", "")
        write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        write(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"$safeName\"\r\n"
                .toByteArray(Charsets.UTF_8)
        )
        write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input -> input.copyTo(this) }
        write("\r\n".toByteArray(Charsets.UTF_8))
    }
}
