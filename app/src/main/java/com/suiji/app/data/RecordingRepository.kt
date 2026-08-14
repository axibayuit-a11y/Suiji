package com.suiji.app.data

import android.content.Context
import com.suiji.app.model.RecordingCategory
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface RecordingRepository {
    fun loadRecordings(): List<RecordingItem>
    fun saveRecording(recording: RecordingItem)
    fun deleteRecording(recording: RecordingItem)
}

class FileRecordingRepository(context: Context) : RecordingRepository {
    private val metadataFile = File(context.filesDir, "recordings.json")

    @Synchronized
    override fun loadRecordings(): List<RecordingItem> {
        if (!metadataFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(metadataFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(item.toRecording())
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun saveRecording(recording: RecordingItem) {
        val updated = loadRecordings().filterNot { it.id == recording.id } + recording
        writeRecordings(updated)
    }

    @Synchronized
    override fun deleteRecording(recording: RecordingItem) {
        writeRecordings(loadRecordings().filterNot { it.id == recording.id })
        recording.audioPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        recording.photoPaths.forEach { File(it).delete() }
    }

    private fun writeRecordings(recordings: List<RecordingItem>) {
        metadataFile.parentFile?.mkdirs()
        val temporaryFile = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
        val array = JSONArray().apply {
            recordings.forEach { put(it.toJson()) }
        }
        temporaryFile.writeText(array.toString())
        if (!temporaryFile.renameTo(metadataFile)) {
            metadataFile.writeText(array.toString())
            temporaryFile.delete()
        }
    }

    private fun RecordingItem.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("durationMs", durationMs)
        put("audioPath", audioPath)
        put("preview", preview)
        put("photoPaths", JSONArray(photoPaths))
        put("isFavorite", isFavorite)
        put("category", category.name)
        put("recordingLanguage", recordingLanguage.name)
        put("transcript", transcript)
        put("timeline", JSONArray().apply {
            timeline.sortedBy { it.timestampMs }.forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id)
                    put("type", event.type.name)
                    put("timestampMs", event.timestampMs)
                    put("endTimestampMs", event.endTimestampMs)
                    put("text", event.text)
                    put("speakerId", event.speakerId)
                    put("photoPath", event.photoPath)
                })
            }
        })
        put("speakerNames", JSONObject().apply {
            speakerNames.forEach { (speakerId, displayName) -> put(speakerId, displayName) }
        })
        put("isOngoing", isOngoing)
        put("transcriptionError", transcriptionError)
    }

    private fun JSONObject.toRecording() = RecordingItem(
        id = getString("id"),
        title = getString("title"),
        createdAt = getLong("createdAt"),
        durationMs = getLong("durationMs"),
        audioPath = getString("audioPath"),
        preview = optString("preview"),
        photoPaths = buildList {
            val paths = optJSONArray("photoPaths") ?: JSONArray()
            for (index in 0 until paths.length()) add(paths.getString(index))
        },
        isFavorite = optBoolean("isFavorite"),
        category = runCatching {
            RecordingCategory.valueOf(optString("category"))
        }.getOrDefault(RecordingCategory.UNCLASSIFIED),
        recordingLanguage = runCatching {
            RecordingLanguage.valueOf(optString("recordingLanguage"))
        }.getOrDefault(RecordingLanguage.CHINESE),
        transcript = optString("transcript"),
        timeline = buildList {
            val events = optJSONArray("timeline") ?: JSONArray()
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                val type = runCatching {
                    TimelineEventType.valueOf(event.optString("type"))
                }.getOrNull() ?: continue
                add(
                    TimelineEvent(
                        id = event.optString("id").ifBlank { "legacy-$index" },
                        type = type,
                        timestampMs = event.optLong("timestampMs").coerceAtLeast(0L),
                        endTimestampMs = event.optLong(
                            "endTimestampMs",
                            event.optLong("timestampMs")
                        ).coerceAtLeast(0L),
                        text = event.optString("text"),
                        speakerId = event.optString("speakerId").takeIf { it.isNotBlank() },
                        photoPath = event.optString("photoPath").takeIf { it.isNotBlank() }
                    )
                )
            }
        }.sortedBy { it.timestampMs },
        speakerNames = buildMap {
            val names = optJSONObject("speakerNames") ?: JSONObject()
            names.keys().forEach { speakerId ->
                names.optString(speakerId).takeIf { it.isNotBlank() }?.let {
                    put(speakerId, it)
                }
            }
        },
        isOngoing = optBoolean("isOngoing"),
        transcriptionError = optString("transcriptionError").takeIf { it.isNotBlank() }
    )
}
