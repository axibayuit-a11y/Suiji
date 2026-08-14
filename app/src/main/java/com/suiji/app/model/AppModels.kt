package com.suiji.app.model

enum class MainTab {
    FILES,
    SETTINGS
}

enum class RootScreen {
    MAIN,
    RECORDER,
    RECORDING_DETAIL,
    AI_SERVICE_SETTINGS,
    SPEECH_MODEL_SETTINGS,
    SPEAKER_DIARIZATION_SETTINGS
}

enum class RecordingLanguage {
    CHINESE,
    ENGLISH,
    CANTONESE_HK
}

enum class UiLanguage(val languageTag: String) {
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
    TRADITIONAL_CHINESE("zh-Hant")
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class TranscriptionMode {
    OFF,
    LOCAL
}

enum class LiveTranscriptionStatus {
    DISABLED,
    MODEL_REQUIRED,
    INITIALIZING,
    LISTENING,
    RECOGNIZING,
    ERROR
}

enum class LocalModelId {
    SENSEVOICE_GENERAL,
    SENSEVOICE_CANTONESE
}

enum class LocalModelOperation {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    FAILED
}

data class LocalModelDescriptor(
    val id: LocalModelId,
    val displayName: String,
    val version: String,
    val downloadUrl: String,
    val archiveBytes: Long,
    val installedBytes: Long,
    val useInverseTextNormalization: Boolean
)

data class LocalModelState(
    val descriptor: LocalModelDescriptor,
    val operation: LocalModelOperation,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = descriptor.archiveBytes,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

enum class SpeakerDiarizationModelId {
    PYANNOTE_3D_SPEAKER
}

data class SpeakerDiarizationModelDescriptor(
    val id: SpeakerDiarizationModelId,
    val displayName: String,
    val version: String,
    val segmentationDownloadUrl: String,
    val embeddingDownloadUrl: String,
    val downloadBytes: Long,
    val installedBytes: Long
)

data class SpeakerDiarizationModelState(
    val descriptor: SpeakerDiarizationModelDescriptor,
    val operation: LocalModelOperation,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = descriptor.downloadBytes,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

enum class FileFilter {
    ALL,
    UNCATEGORIZED,
    FAVORITES,
    MEETING,
    CLASS
}

enum class SortOrder {
    NEWEST,
    OLDEST,
    LONGEST
}

enum class RecordingCategory {
    UNCLASSIFIED,
    MEETING,
    CLASS
}

enum class TimelineEventType {
    SPEECH,
    PHOTO,
    MARKER
}

data class TimelineEvent(
    val id: String,
    val type: TimelineEventType,
    val timestampMs: Long,
    val endTimestampMs: Long = timestampMs,
    val text: String = "",
    val speakerId: String? = null,
    val photoPath: String? = null
)

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: String? = null
)

data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptSegment> = emptyList()
)

data class RecordingItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioPath: String,
    val preview: String = "",
    val photoPaths: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val category: RecordingCategory = RecordingCategory.UNCLASSIFIED,
    val recordingLanguage: RecordingLanguage = RecordingLanguage.CHINESE,
    val transcript: String = "",
    val timeline: List<TimelineEvent> = emptyList(),
    val speakerNames: Map<String, String> = emptyMap(),
    val isOngoing: Boolean = false,
    val transcriptionError: String? = null
)

data class AiServiceConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = ""
) {
    val isReady: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

data class RecordingSessionState(
    val language: RecordingLanguage = RecordingLanguage.CHINESE,
    val elapsedMs: Long = 0L,
    val isPaused: Boolean = false,
    val photoPaths: List<String> = emptyList(),
    val waveform: List<Float> = emptyList(),
    val markerTimestampsMs: List<Long> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val liveTranscript: String = "",
    val liveTranscriptionStatus: LiveTranscriptionStatus = LiveTranscriptionStatus.DISABLED,
    val errorMessage: String? = null
) {
    val photoCount: Int get() = photoPaths.size
}

data class SuijiUiState(
    val rootScreen: RootScreen = RootScreen.MAIN,
    val mainTab: MainTab = MainTab.FILES,
    val recordings: List<RecordingItem> = emptyList(),
    val fileFilter: FileFilter = FileFilter.ALL,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val showFilterSheet: Boolean = false,
    val uiLanguage: UiLanguage = UiLanguage.SIMPLIFIED_CHINESE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val recordingSession: RecordingSessionState = RecordingSessionState(),
    val selectedRecording: RecordingItem? = null,
    val aiServiceConfig: AiServiceConfig = AiServiceConfig(),
    val transcribingIds: Set<String> = emptySet(),
    val transcriptionMode: TranscriptionMode = TranscriptionMode.OFF,
    val selectedLocalModelId: LocalModelId = LocalModelId.SENSEVOICE_GENERAL,
    val localModels: List<LocalModelState> = emptyList(),
    val speakerDiarizationEnabled: Boolean = false,
    val selectedSpeakerDiarizationModelId: SpeakerDiarizationModelId =
        SpeakerDiarizationModelId.PYANNOTE_3D_SPEAKER,
    val speakerDiarizationModels: List<SpeakerDiarizationModelState> = emptyList()
)
