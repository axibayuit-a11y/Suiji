package com.suiji.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.suiji.app.MainActivity
import com.suiji.app.R
import com.suiji.app.data.FileRecordingRepository
import com.suiji.app.model.LiveTranscriptionStatus
import com.suiji.app.model.LocalModelId
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.RecordingSessionState
import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import com.suiji.app.model.TranscriptionMode
import com.suiji.app.transcription.LocalModelManager
import com.suiji.app.transcription.SenseVoiceLocalTranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RecordingServiceState(
    val recordingId: String? = null,
    val createdAt: Long = 0L,
    val session: RecordingSessionState = RecordingSessionState(),
    val isRecording: Boolean = false,
    val isStopping: Boolean = false,
    val completedRecordingId: String? = null
)

class RecordingService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private lateinit var recorder: AudioRecorder
    private lateinit var repository: FileRecordingRepository
    private lateinit var modelManager: LocalModelManager
    private lateinit var localEngine: SenseVoiceLocalTranscriptionEngine
    private var timerJob: Job? = null
    private var recognitionJob: Job? = null
    private var audioFrames: Channel<AudioFrame>? = null
    private var selectedModelId = LocalModelId.SENSEVOICE_GENERAL
    private var transcriptionMode = TranscriptionMode.OFF

    override fun onCreate() {
        super.onCreate()
        recorder = AudioRecorder(this)
        repository = FileRecordingRepository(this)
        modelManager = LocalModelManager(this)
        localEngine = SenseVoiceLocalTranscriptionEngine(modelManager)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_ADD_MARKER -> addMarker()
            ACTION_ADD_PHOTO -> intent.getStringExtra(EXTRA_PHOTO_PATH)?.let(::addPhoto)
            ACTION_STOP -> stopAndSave()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(intent: Intent) {
        if (_state.value.isRecording || recorder.isActive) return
        val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID) ?: UUID.randomUUID().toString()
        val language = enumValueOrDefault(
            intent.getStringExtra(EXTRA_LANGUAGE),
            RecordingLanguage.CHINESE
        )
        transcriptionMode = enumValueOrDefault(
            intent.getStringExtra(EXTRA_TRANSCRIPTION_MODE),
            TranscriptionMode.OFF
        )
        selectedModelId = enumValueOrDefault(
            intent.getStringExtra(EXTRA_MODEL_ID),
            LocalModelId.SENSEVOICE_GENERAL
        )
        val createdAt = System.currentTimeMillis()
        val initialSession = RecordingSessionState(
            language = language,
            liveTranscriptionStatus = initialTranscriptionStatus()
        )
        _state.value = RecordingServiceState(
            recordingId = recordingId,
            createdAt = createdAt,
            session = initialSession,
            isRecording = true
        )
        startForeground(NOTIFICATION_ID, buildNotification(initialSession))

        val started = recorder.start(recordingId, ::onAudioFrame)
        if (started.isFailure) {
            _state.update {
                it.copy(
                    isRecording = false,
                    session = it.session.copy(errorMessage = started.exceptionOrNull()?.message)
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        persistDraft()
        startRecognitionIfAvailable()
        timerJob = serviceScope.launch {
            var notificationTicks = 0
            while (isActive && recorder.isActive) {
                _state.update {
                    it.copy(session = it.session.copy(elapsedMs = recorder.elapsedMs))
                }
                notificationTicks += 1
                if (notificationTicks % 5 == 0) {
                    updateNotification()
                    persistDraft()
                }
                delay(200)
            }
        }
    }

    private fun onAudioFrame(frame: AudioFrame) {
        _state.update { current ->
            val session = current.session
            current.copy(
                session = session.copy(
                    elapsedMs = frame.elapsedMs,
                    waveform = (session.waveform + frame.level).takeLast(MAX_WAVEFORM_SAMPLES)
                )
            )
        }
        audioFrames?.trySend(frame)
    }

    private fun togglePause() {
        val current = _state.value
        if (!current.isRecording || current.isStopping) return
        val result = if (current.session.isPaused) recorder.resume() else recorder.pause()
        if (result.isSuccess) {
            _state.update { it.copy(session = it.session.copy(isPaused = !current.session.isPaused)) }
            updateNotification()
        }
    }

    private fun addMarker() {
        val current = _state.value
        if (!current.isRecording) return
        val timestamp = current.session.elapsedMs
        val event = TimelineEvent(
            id = UUID.randomUUID().toString(),
            type = TimelineEventType.MARKER,
            timestampMs = timestamp
        )
        _state.update {
            it.copy(
                session = it.session.copy(
                    markerTimestampsMs = it.session.markerTimestampsMs + timestamp,
                    timeline = (it.session.timeline + event).sortedBy(TimelineEvent::timestampMs)
                )
            )
        }
        persistDraft()
    }

    private fun addPhoto(path: String) {
        val current = _state.value
        if (!current.isRecording) return
        val event = TimelineEvent(
            id = UUID.randomUUID().toString(),
            type = TimelineEventType.PHOTO,
            timestampMs = current.session.elapsedMs,
            photoPath = path
        )
        _state.update {
            it.copy(
                session = it.session.copy(
                    photoPaths = it.session.photoPaths + path,
                    timeline = (it.session.timeline + event).sortedBy(TimelineEvent::timestampMs)
                )
            )
        }
        persistDraft()
    }

    private fun startRecognitionIfAvailable() {
        if (transcriptionMode != TranscriptionMode.LOCAL) return
        val descriptor = modelManager.descriptor(selectedModelId)
        if (!modelManager.isInstalled(descriptor)) return
        val channel = Channel<AudioFrame>(Channel.BUFFERED)
        audioFrames = channel
        recognitionJob = serviceScope.launch(Dispatchers.Default) {
            var recognizer: OfflineRecognizer? = null
            val speechSamples = ArrayList<Float>(MAX_SPEECH_SAMPLES)
            var speechStartMs = 0L
            var speechEndMs = 0L
            var silenceMs = 0L

            suspend fun transcribePending() {
                if (speechSamples.size < MIN_SPEECH_SAMPLES) {
                    speechSamples.clear()
                    return
                }
                setRecognitionStatus(LiveTranscriptionStatus.RECOGNIZING)
                val text = localEngine.transcribeSamples(
                    checkNotNull(recognizer),
                    speechSamples.toFloatArray()
                ).trim()
                if (text.isNotBlank()) appendSpeechEvent(speechStartMs, speechEndMs, text)
                speechSamples.clear()
                silenceMs = 0L
                setRecognitionStatus(LiveTranscriptionStatus.LISTENING)
            }

            try {
                recognizer = localEngine.createRecognizer(descriptor, _state.value.session.language)
                setRecognitionStatus(LiveTranscriptionStatus.LISTENING)
                for (frame in channel) {
                    val speech = frame.rms >= SPEECH_RMS_THRESHOLD
                    if (speech && speechSamples.isEmpty()) speechStartMs = frame.elapsedMs - FRAME_DURATION_MS
                    if (speech || speechSamples.isNotEmpty()) {
                        speechSamples.addAll(frame.asrSamples.toList())
                        speechEndMs = frame.elapsedMs
                    }
                    silenceMs = if (speech) 0L else if (speechSamples.isNotEmpty()) {
                        silenceMs + FRAME_DURATION_MS
                    } else {
                        0L
                    }
                    if (silenceMs >= END_OF_SPEECH_SILENCE_MS || speechSamples.size >= MAX_SPEECH_SAMPLES) {
                        transcribePending()
                    }
                }
                transcribePending()
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        session = it.session.copy(
                            liveTranscriptionStatus = LiveTranscriptionStatus.ERROR,
                            errorMessage = error.message
                        )
                    )
                }
            } finally {
                recognizer?.release()
            }
        }
    }

    private fun appendSpeechEvent(startMs: Long, endMs: Long, text: String) {
        val event = TimelineEvent(
            id = UUID.randomUUID().toString(),
            type = TimelineEventType.SPEECH,
            timestampMs = startMs.coerceAtLeast(0L),
            endTimestampMs = endMs.coerceAtLeast(startMs),
            text = text
        )
        _state.update {
            val timeline = (it.session.timeline + event).sortedBy(TimelineEvent::timestampMs)
            it.copy(
                session = it.session.copy(
                    timeline = timeline,
                    liveTranscript = timeline
                        .filter { item -> item.type == TimelineEventType.SPEECH }
                        .joinToString("\n") { item -> item.text }
                )
            )
        }
        persistDraft()
    }

    private fun stopAndSave() {
        val current = _state.value
        if (!current.isRecording || current.isStopping) return
        _state.update { it.copy(isStopping = true) }
        serviceScope.launch(Dispatchers.IO) {
            timerJob?.cancel()
            val recordedAudio = recorder.stop().getOrNull()
            audioFrames?.close()
            audioFrames = null
            recognitionJob?.join()
            recognitionJob = null
            if (recordedAudio != null) {
                val finalState = _state.value
                val recording = buildRecordingItem(
                    id = checkNotNull(finalState.recordingId),
                    createdAt = finalState.createdAt,
                    session = finalState.session,
                    audioPath = recordedAudio.file.absolutePath,
                    durationMs = recordedAudio.durationMs,
                    isOngoing = false
                )
                repository.saveRecording(recording)
                _state.value = RecordingServiceState(completedRecordingId = recording.id)
            } else {
                _state.value = RecordingServiceState()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun persistDraft() {
        val current = _state.value
        val id = current.recordingId ?: return
        val audioPath = java.io.File(filesDir, "recordings/$id.wav").absolutePath
        repository.saveRecording(
            buildRecordingItem(
                id = id,
                createdAt = current.createdAt,
                session = current.session,
                audioPath = audioPath,
                durationMs = current.session.elapsedMs,
                isOngoing = true
            )
        )
    }

    private fun buildRecordingItem(
        id: String,
        createdAt: Long,
        session: RecordingSessionState,
        audioPath: String,
        durationMs: Long,
        isOngoing: Boolean
    ): RecordingItem {
        val titleTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
        val speech = session.timeline.filter { it.type == TimelineEventType.SPEECH }
        val transcript = speech.joinToString("\n") { event ->
            event.speakerId?.let { "$it: ${event.text}" } ?: event.text
        }
        return RecordingItem(
            id = id,
            title = getString(R.string.recording_default_title, titleTime),
            createdAt = createdAt,
            durationMs = durationMs,
            audioPath = audioPath,
            preview = transcript.take(160),
            photoPaths = session.photoPaths,
            recordingLanguage = session.language,
            transcript = transcript,
            timeline = session.timeline.sortedBy(TimelineEvent::timestampMs),
            isOngoing = isOngoing
        )
    }

    private fun initialTranscriptionStatus(): LiveTranscriptionStatus = when (transcriptionMode) {
        TranscriptionMode.OFF -> LiveTranscriptionStatus.DISABLED
        TranscriptionMode.CLOUD -> LiveTranscriptionStatus.CLOUD_AFTER_RECORDING
        TranscriptionMode.LOCAL -> {
            val descriptor = modelManager.descriptor(selectedModelId)
            if (modelManager.isInstalled(descriptor)) LiveTranscriptionStatus.INITIALIZING
            else LiveTranscriptionStatus.MODEL_REQUIRED
        }
    }

    private fun setRecognitionStatus(status: LiveTranscriptionStatus) {
        _state.update { it.copy(session = it.session.copy(liveTranscriptionStatus = status)) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(session: RecordingSessionState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_RECORDER, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, RecordingService::class.java).setAction(ACTION_PAUSE_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(formatElapsed(session.elapsedMs))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                0,
                getString(if (session.isPaused) R.string.resume else R.string.pause),
                pauseIntent
            )
            .addAction(0, getString(R.string.stop_and_save), stopIntent)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(_state.value.session))
    }

    override fun onDestroy() {
        if (recorder.isActive && !_state.value.isStopping) {
            val current = _state.value
            val audio = recorder.stop().getOrNull()
            if (audio != null && current.recordingId != null) {
                repository.saveRecording(
                    buildRecordingItem(
                        current.recordingId,
                        current.createdAt,
                        current.session,
                        audio.file.absolutePath,
                        audio.durationMs,
                        false
                    )
                )
            }
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.suiji.app.action.START_RECORDING"
        private const val ACTION_PAUSE_RESUME = "com.suiji.app.action.PAUSE_RESUME_RECORDING"
        private const val ACTION_ADD_MARKER = "com.suiji.app.action.ADD_RECORDING_MARKER"
        private const val ACTION_ADD_PHOTO = "com.suiji.app.action.ADD_RECORDING_PHOTO"
        private const val ACTION_STOP = "com.suiji.app.action.STOP_RECORDING"
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val EXTRA_LANGUAGE = "recording_language"
        private const val EXTRA_TRANSCRIPTION_MODE = "transcription_mode"
        private const val EXTRA_MODEL_ID = "local_model_id"
        private const val EXTRA_PHOTO_PATH = "photo_path"
        const val EXTRA_OPEN_RECORDER = "open_recorder"
        private const val NOTIFICATION_CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_WAVEFORM_SAMPLES = 180
        private const val SPEECH_RMS_THRESHOLD = 0.0025f
        private const val FRAME_DURATION_MS = 100L
        private const val END_OF_SPEECH_SILENCE_MS = 700L
        private const val MIN_SPEECH_SAMPLES = 16_000 / 2
        private const val MAX_SPEECH_SAMPLES = 16_000 * 12

        private val _state = MutableStateFlow(RecordingServiceState())
        val state: StateFlow<RecordingServiceState> = _state.asStateFlow()

        fun start(
            context: Context,
            recordingId: String,
            language: RecordingLanguage,
            transcriptionMode: TranscriptionMode,
            modelId: LocalModelId
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RECORDING_ID, recordingId)
                    putExtra(EXTRA_LANGUAGE, language.name)
                    putExtra(EXTRA_TRANSCRIPTION_MODE, transcriptionMode.name)
                    putExtra(EXTRA_MODEL_ID, modelId.name)
                }
            )
        }

        fun togglePause(context: Context) = send(context, ACTION_PAUSE_RESUME)
        fun addMarker(context: Context) = send(context, ACTION_ADD_MARKER)
        fun addPhoto(context: Context, path: String) {
            context.startService(
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_ADD_PHOTO
                    putExtra(EXTRA_PHOTO_PATH, path)
                }
            )
        }
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, actionName: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(actionName))
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
            value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

        private fun formatElapsed(elapsedMs: Long): String {
            val totalSeconds = elapsedMs / 1000
            return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }
}
