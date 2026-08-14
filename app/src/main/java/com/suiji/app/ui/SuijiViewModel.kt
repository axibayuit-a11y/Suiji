package com.suiji.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suiji.app.data.AppPreferences
import com.suiji.app.data.FileRecordingRepository
import com.suiji.app.model.FileFilter
import com.suiji.app.model.AiServiceConfig
import com.suiji.app.model.LocalModelId
import com.suiji.app.model.LocalModelOperation
import com.suiji.app.model.LocalModelState
import com.suiji.app.model.SpeakerDiarizationModelId
import com.suiji.app.model.SpeakerDiarizationModelState
import com.suiji.app.model.LiveTranscriptionStatus
import com.suiji.app.model.MainTab
import com.suiji.app.model.RecordingCategory
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.RecordingSessionState
import com.suiji.app.model.RootScreen
import com.suiji.app.model.SortOrder
import com.suiji.app.model.SuijiUiState
import com.suiji.app.model.ThemeMode
import com.suiji.app.model.TranscriptionMode
import com.suiji.app.model.TranscriptionResult
import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import com.suiji.app.model.UiLanguage
import com.suiji.app.recording.RecordingService
import com.suiji.app.transcription.LocalModelManager
import com.suiji.app.transcription.SenseVoiceLocalTranscriptionEngine
import com.suiji.app.speaker.LocalSpeakerDiarizationEngine
import com.suiji.app.speaker.SpeakerAttribution
import com.suiji.app.speaker.SpeakerDiarizationModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class SuijiViewModel(application: Application) : AndroidViewModel(application) {
    private val recordingRepository = FileRecordingRepository(application)
    private val preferences = AppPreferences(application)
    private val appContext = application.applicationContext
    private val localModelManager = LocalModelManager(application)
    private val localTranscriptionEngine = SenseVoiceLocalTranscriptionEngine(localModelManager)
    private val speakerModelManager = SpeakerDiarizationModelManager(application)
    private val localDiarizationEngine = LocalSpeakerDiarizationEngine(speakerModelManager)
    private val modelDownloadJobs = mutableMapOf<LocalModelId, Job>()
    private val speakerModelDownloadJobs = mutableMapOf<SpeakerDiarizationModelId, Job>()
    private var activeRecordingId: String? = null
    private var handledCompletedRecordingId: String? = null

    private val _uiState = MutableStateFlow(
        SuijiUiState(
            recordings = recordingRepository.loadRecordings(),
            uiLanguage = preferences.readUiLanguage(),
            themeMode = preferences.readThemeMode(),
            aiServiceConfig = preferences.readAiServiceConfig(),
            transcriptionMode = preferences.readTranscriptionMode(),
            selectedLocalModelId = preferences.readSelectedLocalModel(),
            localModels = localModelManager.currentStates(),
            speakerDiarizationEnabled = preferences.readSpeakerDiarizationEnabled(),
            selectedSpeakerDiarizationModelId = preferences.readSelectedSpeakerDiarizationModel(),
            speakerDiarizationModels = speakerModelManager.currentStates()
        )
    )
    val uiState: StateFlow<SuijiUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            RecordingService.state.collect { runtime ->
                when {
                    runtime.isRecording -> {
                        activeRecordingId = runtime.recordingId
                        _uiState.update {
                            it.copy(
                                rootScreen = RootScreen.RECORDER,
                                recordingSession = runtime.session
                            )
                        }
                    }

                    runtime.completedRecordingId != null &&
                        runtime.completedRecordingId != handledCompletedRecordingId -> {
                        handledCompletedRecordingId = runtime.completedRecordingId
                        activeRecordingId = null
                        val recordings = recordingRepository.loadRecordings()
                        val completed = recordings.firstOrNull { it.id == runtime.completedRecordingId }
                        _uiState.update {
                            it.copy(
                                rootScreen = RootScreen.MAIN,
                                mainTab = MainTab.FILES,
                                recordings = recordings.sortedByDescending(RecordingItem::createdAt),
                                recordingSession = RecordingSessionState(
                                    language = it.recordingSession.language
                                ),
                                selectedRecording = null
                            )
                        }
                        completed?.let(::startAutomaticTranscription)
                    }
                }
            }
        }
    }

    fun selectMainTab(tab: MainTab) {
        _uiState.update { it.copy(mainTab = tab) }
    }

    fun openRecording(recording: RecordingItem) {
        _uiState.update {
            it.copy(
                rootScreen = RootScreen.RECORDING_DETAIL,
                selectedRecording = recording
            )
        }
    }

    fun closeRecording() {
        _uiState.update {
            it.copy(
                rootScreen = RootScreen.MAIN,
                mainTab = MainTab.FILES,
                selectedRecording = null
            )
        }
    }

    fun openAiServiceSettings() {
        _uiState.update { it.copy(rootScreen = RootScreen.AI_SERVICE_SETTINGS) }
    }

    fun closeAiServiceSettings() {
        _uiState.update {
            it.copy(rootScreen = RootScreen.MAIN, mainTab = MainTab.SETTINGS)
        }
    }

    fun openSpeechModelSettings() {
        _uiState.update { it.copy(rootScreen = RootScreen.SPEECH_MODEL_SETTINGS) }
    }

    fun closeSpeechModelSettings() {
        _uiState.update {
            it.copy(rootScreen = RootScreen.MAIN, mainTab = MainTab.SETTINGS)
        }
    }

    fun openSpeakerDiarizationSettings() {
        _uiState.update { it.copy(rootScreen = RootScreen.SPEAKER_DIARIZATION_SETTINGS) }
    }

    fun closeSpeakerDiarizationSettings() {
        _uiState.update { it.copy(rootScreen = RootScreen.MAIN, mainTab = MainTab.SETTINGS) }
    }

    fun setTranscriptionMode(mode: TranscriptionMode) {
        if (mode == TranscriptionMode.LOCAL) {
            val descriptor = localModelManager.descriptor(_uiState.value.selectedLocalModelId)
            if (!localModelManager.isInstalled(descriptor)) {
                openSpeechModelSettings()
                return
            }
        }
        preferences.writeTranscriptionMode(mode)
        _uiState.update { it.copy(transcriptionMode = mode) }
    }

    fun selectLocalModel(id: LocalModelId) {
        preferences.writeSelectedLocalModel(id)
        _uiState.update { it.copy(selectedLocalModelId = id) }
    }

    fun downloadLocalModel(id: LocalModelId) {
        if (modelDownloadJobs[id]?.isActive == true) return
        val descriptor = localModelManager.descriptor(id)
        updateLocalModelState(
            LocalModelState(
                descriptor = descriptor,
                operation = LocalModelOperation.DOWNLOADING,
                downloadedBytes = _uiState.value.localModels
                    .firstOrNull { it.descriptor.id == id }
                    ?.downloadedBytes ?: 0L
            )
        )
        modelDownloadJobs[id] = viewModelScope.launch {
            runCatching {
                localModelManager.downloadAndInstall(descriptor, ::updateLocalModelState)
            }
            modelDownloadJobs.remove(id)
        }
    }

    fun cancelLocalModelDownload(id: LocalModelId) {
        modelDownloadJobs.remove(id)?.cancel()
        _uiState.update { state ->
            state.copy(
                localModels = localModelManager.currentStates().map { fresh ->
                    state.localModels.firstOrNull {
                        it.descriptor.id == fresh.descriptor.id &&
                            it.operation == LocalModelOperation.INSTALLED
                    } ?: fresh
                }
            )
        }
    }

    fun deleteLocalModel(id: LocalModelId) {
        modelDownloadJobs.remove(id)?.cancel()
        viewModelScope.launch {
            localModelManager.deleteModel(id)
            val switchOff = _uiState.value.selectedLocalModelId == id &&
                _uiState.value.transcriptionMode == TranscriptionMode.LOCAL
            if (switchOff) preferences.writeTranscriptionMode(TranscriptionMode.OFF)
            _uiState.update {
                it.copy(
                    localModels = localModelManager.currentStates(),
                    transcriptionMode = if (switchOff) TranscriptionMode.OFF else it.transcriptionMode
                )
            }
        }
    }

    private fun updateLocalModelState(modelState: LocalModelState) {
        if (modelState.operation == LocalModelOperation.INSTALLED) {
            preferences.writeSelectedLocalModel(modelState.descriptor.id)
            preferences.writeTranscriptionMode(TranscriptionMode.LOCAL)
        }
        _uiState.update { state ->
            state.copy(
                localModels = state.localModels.map {
                    if (it.descriptor.id == modelState.descriptor.id) modelState else it
                },
                selectedLocalModelId = if (
                    modelState.operation == LocalModelOperation.INSTALLED
                ) {
                    modelState.descriptor.id
                } else {
                    state.selectedLocalModelId
                },
                transcriptionMode = if (
                    modelState.operation == LocalModelOperation.INSTALLED
                ) {
                    TranscriptionMode.LOCAL
                } else {
                    state.transcriptionMode
                }
            )
        }
    }

    fun saveAiServiceConfig(config: AiServiceConfig) {
        val normalized = config.copy(
            baseUrl = config.baseUrl.trim().trimEnd('/'),
            apiKey = config.apiKey.trim(),
            model = config.model.trim()
        )
        preferences.writeAiServiceConfig(normalized)
        _uiState.update {
            it.copy(aiServiceConfig = normalized)
        }
    }

    fun setSpeakerDiarizationEnabled(enabled: Boolean) {
        if (enabled) {
            val state = _uiState.value
            val descriptor = speakerModelManager.descriptor(
                state.selectedSpeakerDiarizationModelId
            )
            if (!speakerModelManager.isInstalled(descriptor)) {
                openSpeakerDiarizationSettings()
                return
            }
        }
        preferences.writeSpeakerDiarizationEnabled(enabled)
        _uiState.update { it.copy(speakerDiarizationEnabled = enabled) }
    }

    fun selectSpeakerDiarizationModel(id: SpeakerDiarizationModelId) {
        preferences.writeSelectedSpeakerDiarizationModel(id)
        _uiState.update { it.copy(selectedSpeakerDiarizationModelId = id) }
    }

    fun downloadSpeakerDiarizationModel(id: SpeakerDiarizationModelId) {
        if (speakerModelDownloadJobs[id]?.isActive == true) return
        val descriptor = speakerModelManager.descriptor(id)
        updateSpeakerDiarizationModelState(
            SpeakerDiarizationModelState(
                descriptor = descriptor,
                operation = LocalModelOperation.DOWNLOADING,
                downloadedBytes = _uiState.value.speakerDiarizationModels
                    .firstOrNull { it.descriptor.id == id }
                    ?.downloadedBytes ?: 0L
            )
        )
        speakerModelDownloadJobs[id] = viewModelScope.launch {
            runCatching {
                speakerModelManager.downloadAndInstall(
                    descriptor,
                    ::updateSpeakerDiarizationModelState
                )
            }
            speakerModelDownloadJobs.remove(id)
        }
    }

    fun cancelSpeakerDiarizationModelDownload(id: SpeakerDiarizationModelId) {
        speakerModelDownloadJobs.remove(id)?.cancel()
        _uiState.update { it.copy(speakerDiarizationModels = speakerModelManager.currentStates()) }
    }

    fun deleteSpeakerDiarizationModel(id: SpeakerDiarizationModelId) {
        speakerModelDownloadJobs.remove(id)?.cancel()
        viewModelScope.launch {
            speakerModelManager.deleteModel(id)
            preferences.writeSpeakerDiarizationEnabled(false)
            _uiState.update {
                it.copy(
                    speakerDiarizationEnabled = false,
                    speakerDiarizationModels = speakerModelManager.currentStates()
                )
            }
        }
    }

    private fun updateSpeakerDiarizationModelState(modelState: SpeakerDiarizationModelState) {
        if (modelState.operation == LocalModelOperation.INSTALLED) {
            preferences.writeSelectedSpeakerDiarizationModel(modelState.descriptor.id)
        }
        _uiState.update { state ->
            state.copy(
                speakerDiarizationModels = state.speakerDiarizationModels.map {
                    if (it.descriptor.id == modelState.descriptor.id) modelState else it
                },
                selectedSpeakerDiarizationModelId = if (
                    modelState.operation == LocalModelOperation.INSTALLED
                ) {
                    modelState.descriptor.id
                } else {
                    state.selectedSpeakerDiarizationModelId
                }
            )
        }
    }

    fun toggleFavorite(recording: RecordingItem) {
        updateRecording(recording.copy(isFavorite = !recording.isFavorite))
    }

    fun setRecordingCategory(recording: RecordingItem, category: RecordingCategory) {
        updateRecording(recording.copy(category = category))
    }

    fun renameSpeaker(recording: RecordingItem, speakerId: String, displayName: String) {
        val normalized = displayName.trim()
        val names = recording.speakerNames.toMutableMap().apply {
            if (normalized.isBlank()) remove(speakerId) else put(speakerId, normalized)
        }
        updateRecording(recording.copy(speakerNames = names))
    }

    fun deleteRecording(recording: RecordingItem) {
        recordingRepository.deleteRecording(recording)
        _uiState.update {
            it.copy(
                rootScreen = RootScreen.MAIN,
                mainTab = MainTab.FILES,
                recordings = it.recordings.filterNot { item -> item.id == recording.id },
                selectedRecording = null
            )
        }
    }

    fun retryTranscription(recording: RecordingItem) {
        startAutomaticTranscription(recording)
    }

    private fun updateRecording(recording: RecordingItem) {
        recordingRepository.saveRecording(recording)
        _uiState.update {
            it.copy(
                recordings = it.recordings.map { item ->
                    if (item.id == recording.id) recording else item
                },
                selectedRecording = if (it.selectedRecording?.id == recording.id) {
                    recording
                } else {
                    it.selectedRecording
                }
            )
        }
    }

    fun showFilterSheet(show: Boolean) {
        _uiState.update { it.copy(showFilterSheet = show) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectFileFilter(filter: FileFilter) {
        _uiState.update { it.copy(fileFilter = filter) }
    }

    fun selectSortOrder(order: SortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
    }

    fun setUiLanguage(language: UiLanguage) {
        preferences.writeUiLanguage(language)
        _uiState.update { it.copy(uiLanguage = language) }
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.writeThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setRecordingLanguage(language: RecordingLanguage) {
        _uiState.update {
            it.copy(recordingSession = it.recordingSession.copy(language = language))
        }
    }

    fun startRecording(): Boolean {
        if (RecordingService.state.value.isRecording) {
            _uiState.update {
                it.copy(
                    rootScreen = RootScreen.RECORDER,
                    recordingSession = RecordingService.state.value.session
                )
            }
            return true
        }
        val recordingId = UUID.randomUUID().toString()
        activeRecordingId = recordingId
        val state = _uiState.value
        _uiState.update {
            it.copy(
                rootScreen = RootScreen.RECORDER,
                recordingSession = RecordingSessionState(
                    language = it.recordingSession.language,
                    liveTranscriptionStatus = initialLiveTranscriptionStatus(it)
                )
            )
        }
        RecordingService.start(
            context = appContext,
            recordingId = recordingId,
            language = state.recordingSession.language,
            transcriptionMode = state.transcriptionMode,
            modelId = state.selectedLocalModelId
        )
        return true
    }

    fun addRecordingMarker() {
        RecordingService.addMarker(appContext)
    }

    fun togglePause() {
        RecordingService.togglePause(appContext)
    }

    fun onPhotoCaptured(path: String) {
        RecordingService.addPhoto(appContext, path)
    }

    fun clearRecordingError() {
        _uiState.update {
            it.copy(recordingSession = it.recordingSession.copy(errorMessage = null))
        }
    }

    fun stopRecording(title: String): Boolean {
        if (!RecordingService.state.value.isRecording) return false
        RecordingService.stop(appContext)
        return true
    }

    private fun startAutomaticTranscription(recording: RecordingItem) {
        val state = _uiState.value
        if (recording.id in state.transcribingIds) return
        if (state.transcriptionMode == TranscriptionMode.OFF) return
        val descriptor = localModelManager.descriptor(state.selectedLocalModelId)
        if (!localModelManager.isInstalled(descriptor)) return
        val audioFile = java.io.File(recording.audioPath)
        if (!audioFile.isFile) return

        _uiState.update {
            it.copy(
                transcribingIds = it.transcribingIds + recording.id,
                recordings = it.recordings.map { item ->
                    if (item.id == recording.id) item.copy(transcriptionError = null) else item
                },
                selectedRecording = it.selectedRecording?.let { selected ->
                    if (selected.id == recording.id) selected.copy(transcriptionError = null) else selected
                }
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = localTranscriptionEngine.transcribeWithSegments(
                audioFile,
                descriptor,
                recording.recordingLanguage
            ).mapCatching { transcription ->
                if (!state.speakerDiarizationEnabled) return@mapCatching transcription
                val speakerDescriptor = speakerModelManager.descriptor(
                    state.selectedSpeakerDiarizationModelId
                )
                if (!speakerModelManager.isInstalled(speakerDescriptor)) {
                    return@mapCatching transcription
                }
                val ranges = localDiarizationEngine.diarize(
                    audioFile,
                    state.selectedSpeakerDiarizationModelId
                ).getOrThrow()
                SpeakerAttribution.apply(transcription, ranges)
            }
            if (_uiState.value.recordings.none { it.id == recording.id }) {
                _uiState.update { it.copy(transcribingIds = it.transcribingIds - recording.id) }
                return@launch
            }
            val current = _uiState.value.recordings.firstOrNull { it.id == recording.id } ?: recording
            val updated = result.fold(
                onSuccess = { transcription ->
                    val timedSpeech = transcription.segments.mapIndexed { index, segment ->
                        TimelineEvent(
                            id = "transcript-${recording.id}-$index",
                            type = TimelineEventType.SPEECH,
                            timestampMs = segment.startMs,
                            endTimestampMs = segment.endMs,
                            text = segment.text,
                            speakerId = segment.speakerId
                        )
                    }
                    val timeline = if (timedSpeech.isNotEmpty()) {
                        (current.timeline.filter { it.type != TimelineEventType.SPEECH } + timedSpeech)
                            .sortedBy(TimelineEvent::timestampMs)
                    } else {
                        current.timeline
                    }
                    current.copy(
                        transcript = transcription.text,
                        preview = transcription.text.take(160),
                        timeline = timeline,
                        transcriptionError = null
                    )
                },
                onFailure = { error ->
                    current.copy(
                        transcriptionError = error.message ?: "Transcription failed"
                    )
                }
            )
            recordingRepository.saveRecording(updated)
            _uiState.update {
                it.copy(
                    recordings = it.recordings.map { item ->
                        if (item.id == updated.id) updated else item
                    },
                    selectedRecording = if (it.selectedRecording?.id == updated.id) {
                        updated
                    } else {
                        it.selectedRecording
                    },
                    transcribingIds = it.transcribingIds - updated.id
                )
            }
        }
    }

    private fun initialLiveTranscriptionStatus(state: SuijiUiState): LiveTranscriptionStatus =
        when (state.transcriptionMode) {
            TranscriptionMode.OFF -> LiveTranscriptionStatus.DISABLED
            TranscriptionMode.LOCAL -> {
                val descriptor = localModelManager.descriptor(state.selectedLocalModelId)
                if (localModelManager.isInstalled(descriptor)) {
                    LiveTranscriptionStatus.INITIALIZING
                } else {
                    LiveTranscriptionStatus.MODEL_REQUIRED
                }
            }
        }

}
