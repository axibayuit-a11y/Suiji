package com.suiji.app.model

enum class MainTab {
    FILES,
    SETTINGS
}

enum class RootScreen {
    MAIN,
    RECORDER,
    RECORDING_DETAIL,
    CLOUD_TRANSCRIPTION_SETTINGS,
    LOCAL_MODEL_SETTINGS
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
    LOCAL,
    CLOUD
}

enum class LiveTranscriptionStatus {
    DISABLED,
    MODEL_REQUIRED,
    CLOUD_AFTER_RECORDING,
    INITIALIZING,
    LISTENING,
    RECOGNIZING,
    ERROR
}

enum class LocalModelId {
    SENSEVOICE_GENERAL,
    SENSEVOICE_CANTONESE,
    SPEAKER_DIARIZATION
}

enum class LocalModelKind {
    SPEECH_RECOGNITION,
    SPEAKER_DIARIZATION
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
    val useInverseTextNormalization: Boolean,
    val kind: LocalModelKind = LocalModelKind.SPEECH_RECOGNITION,
    val secondaryDownloadUrl: String? = null
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

data class CloudTranscriptionConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-transcribe",
    val speakerDiarization: Boolean = false
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
    val cloudTranscriptionConfig: CloudTranscriptionConfig = CloudTranscriptionConfig(),
    val transcribingIds: Set<String> = emptySet(),
    val transcriptionMode: TranscriptionMode = TranscriptionMode.OFF,
    val selectedLocalModelId: LocalModelId = LocalModelId.SENSEVOICE_GENERAL,
    val localModels: List<LocalModelState> = emptyList()
)
