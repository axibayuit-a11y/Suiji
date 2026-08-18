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
import com.suiji.app.model.LsEendModelId
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.RecordingSessionState
import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import com.suiji.app.model.TranscriptionMode
import com.suiji.app.transcription.LocalModelManager
import com.suiji.app.transcription.NaturalSpeechSegment
import com.suiji.app.transcription.NaturalSpeechSegmenter
import com.suiji.app.transcription.SenseVoiceLocalTranscriptionEngine
import com.suiji.app.speaker.LiveConversationTimeline
import com.suiji.app.speaker.LsEendModelManager
import com.suiji.app.speaker.RealtimeSpeakerDiarizer
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
import java.util.ArrayDeque
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
    private lateinit var speechSegmenter: NaturalSpeechSegmenter
    private lateinit var speakerModelManager: LsEendModelManager
    private var timerJob: Job? = null
    private var recognitionJob: Job? = null
    private var speakerJob: Job? = null
    private var audioFrames: Channel<AudioFrame>? = null
    private var speakerFrames: Channel<AudioFrame>? = null
    private var liveConversation: LiveConversationTimeline? = null
    private var selectedModelId = LocalModelId.SENSEVOICE_GENERAL
    private var selectedSpeakerModelId = LsEendModelId.GENERIC_1_8
    private var speakerDiarizationEnabled = false
    private var transcriptionMode = TranscriptionMode.OFF

    override fun onCreate() {
        super.onCreate()
        recorder = AudioRecorder(this)
        repository = FileRecordingRepository(this)
        modelManager = LocalModelManager(this)
        localEngine = SenseVoiceLocalTranscriptionEngine(modelManager, this)
        speechSegmenter = NaturalSpeechSegmenter(this)
        speakerModelManager = LsEendModelManager(this)
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
        speakerDiarizationEnabled = intent.getBooleanExtra(
            EXTRA_SPEAKER_DIARIZATION_ENABLED,
            false
        )
        selectedSpeakerModelId = enumValueOrDefault(
            intent.getStringExtra(EXTRA_SPEAKER_MODEL_ID),
            LsEendModelId.GENERIC_1_8
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
        startAudioAnalysisIfAvailable()
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
        speakerFrames?.trySend(frame)
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

    private fun startAudioAnalysisIfAvailable() {
        val conversation = LiveConversationTimeline()
        liveConversation = conversation
        startTranscriptionIfAvailable(conversation)
        startSpeakerTrackingIfAvailable(conversation)
    }

    private fun startTranscriptionIfAvailable(conversation: LiveConversationTimeline) {
        if (transcriptionMode != TranscriptionMode.LOCAL) return
        val descriptor = modelManager.descriptor(selectedModelId)
        if (!modelManager.isInstalled(descriptor)) return
        val channel = Channel<AudioFrame>(Channel.UNLIMITED)
        audioFrames = channel
        recognitionJob = serviceScope.launch(Dispatchers.Default) {
            var recognizer: OfflineRecognizer? = null
            var segmenterSession: NaturalSpeechSegmenter.Session? = null
            val pendingFrames = ArrayDeque<AudioFrame>()
            var committedThroughMs = 0L
            var lastPartialRefreshMs = 0L
            var lastVoiceMs = Long.MIN_VALUE

            fun recognizeAndCommit(segment: NaturalSpeechSegment) {
                val effectiveStartMs = maxOf(segment.startMs, committedThroughMs)
                if (segment.endMs <= effectiveStartMs) return
                val offset = ((effectiveStartMs - segment.startMs) * ASR_SAMPLE_RATE / 1000L)
                    .toInt()
                    .coerceIn(0, segment.samples.size)
                val remaining = segment.samples.copyOfRange(offset, segment.samples.size)
                val text = if (remaining.size >= MIN_LIVE_RECOGNITION_SAMPLES) {
                    setRecognitionStatus(LiveTranscriptionStatus.RECOGNIZING)
                    localEngine.transcribeSamples(checkNotNull(recognizer), remaining).trim()
                } else {
                    ""
                }
                conversation.commit(effectiveStartMs, segment.endMs, text)
                publishConversationTimeline()
                committedThroughMs = maxOf(committedThroughMs, segment.endMs)
                pruneFramesThrough(pendingFrames, committedThroughMs)
                setRecognitionStatus(LiveTranscriptionStatus.LISTENING)
            }

            try {
                recognizer = localEngine.createRecognizer(descriptor, _state.value.session.language)
                segmenterSession = speechSegmenter.openSession()
                setRecognitionStatus(LiveTranscriptionStatus.LISTENING)
                for (frame in channel) {
                    pendingFrames.addLast(frame)
                    if (frame.rms >= LIVE_VOICE_RMS_THRESHOLD) lastVoiceMs = frame.elapsedMs
                    segmenterSession.accept(frame.asrSamples).forEach(::recognizeAndCommit)
                    pruneFramesBefore(
                        pendingFrames,
                        frame.elapsedMs - MAX_LIVE_BUFFER_RETENTION_MS
                    )

                    val pendingStartMs = pendingFrames.peekFirst()?.let(::audioFrameStartMs)
                        ?.coerceAtLeast(committedThroughMs)
                    val pendingDurationMs = pendingStartMs?.let { frame.elapsedMs - it } ?: 0L

                    if (
                        lastVoiceMs != Long.MIN_VALUE &&
                        pendingDurationMs >= SAFETY_COMMIT_AFTER_MS
                    ) {
                        val safetyEndMs = checkNotNull(pendingStartMs) + SAFETY_COMMIT_WINDOW_MS
                        val samples = collectSamples(pendingFrames, pendingStartMs, safetyEndMs)
                        if (samples.size >= MIN_LIVE_RECOGNITION_SAMPLES) {
                            setRecognitionStatus(LiveTranscriptionStatus.RECOGNIZING)
                            val text = localEngine.transcribeSamples(checkNotNull(recognizer), samples)
                            conversation.commit(pendingStartMs, safetyEndMs, text)
                            publishConversationTimeline()
                            committedThroughMs = safetyEndMs
                            pruneFramesThrough(pendingFrames, committedThroughMs)
                            setRecognitionStatus(LiveTranscriptionStatus.LISTENING)
                        }
                    }

                    val shouldRefreshPartial =
                        lastVoiceMs != Long.MIN_VALUE &&
                        frame.elapsedMs - lastPartialRefreshMs >= LIVE_REFRESH_INTERVAL_MS &&
                            frame.elapsedMs - lastVoiceMs <= LIVE_VOICE_HOLD_MS
                    if (shouldRefreshPartial) {
                        val startMs = pendingFrames.peekFirst()?.let(::audioFrameStartMs)
                            ?.coerceAtLeast(committedThroughMs)
                        if (startMs != null && frame.elapsedMs - startMs >= MIN_PARTIAL_AUDIO_MS) {
                            val windowStartMs = maxOf(
                                startMs,
                                frame.elapsedMs - MAX_PARTIAL_WINDOW_MS
                            )
                            val samples = collectSamples(
                                pendingFrames,
                                windowStartMs,
                                frame.elapsedMs
                            )
                            if (samples.size >= MIN_LIVE_RECOGNITION_SAMPLES) {
                                setRecognitionStatus(LiveTranscriptionStatus.RECOGNIZING)
                                val text = localEngine.transcribeSamples(
                                    checkNotNull(recognizer),
                                    samples
                                )
                                if (text.isNotBlank()) {
                                    conversation.updatePartial(
                                        windowStartMs,
                                        frame.elapsedMs,
                                        text
                                    )
                                    publishConversationTimeline()
                                }
                                setRecognitionStatus(LiveTranscriptionStatus.LISTENING)
                            }
                        }
                        lastPartialRefreshMs = frame.elapsedMs
                    }
                }
                segmenterSession.flush().forEach(::recognizeAndCommit)
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
                segmenterSession?.close()
                recognizer?.release()
            }
        }
    }

    private fun startSpeakerTrackingIfAvailable(conversation: LiveConversationTimeline) {
        if (!speakerDiarizationEnabled) return
        val installedModel = runCatching {
            speakerModelManager.requireInstalled(selectedSpeakerModelId)
        }.getOrElse { error ->
            _state.update {
                it.copy(session = it.session.copy(errorMessage = error.message))
            }
            return
        }
        conversation.enableSpeakerTracking()
        val channel = Channel<AudioFrame>(Channel.UNLIMITED)
        speakerFrames = channel
        speakerJob = serviceScope.launch(Dispatchers.Default) {
            var diarizer: RealtimeSpeakerDiarizer? = null
            try {
                diarizer = RealtimeSpeakerDiarizer(installedModel)
                for (frame in channel) {
                    val activity = diarizer.accept(frame.speakerSamples)
                    if (activity.isEmpty()) continue
                    activity.forEach(conversation::observeSpeakerActivity)
                    publishConversationTimeline()
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(session = it.session.copy(errorMessage = error.message))
                }
            } finally {
                diarizer?.close()
            }
        }
    }

    private fun publishConversationTimeline() {
        val speechEvents = liveConversation?.visibleEvents().orEmpty()
        _state.update { current ->
            val timeline = (
                current.session.timeline.filter { it.type != TimelineEventType.SPEECH } +
                    speechEvents
                ).sortedBy(TimelineEvent::timestampMs)
            current.copy(
                session = current.session.copy(
                    timeline = timeline,
                    liveTranscript = speechEvents.joinToString("\n", transform = TimelineEvent::text)
                )
            )
        }
    }

    private fun audioFrameStartMs(frame: AudioFrame): Long =
        frame.elapsedMs - frame.asrSamples.size * 1000L / ASR_SAMPLE_RATE

    private fun pruneFramesThrough(frames: ArrayDeque<AudioFrame>, timestampMs: Long) {
        while (frames.peekFirst()?.elapsedMs?.let { it <= timestampMs } == true) {
            frames.removeFirst()
        }
    }

    private fun pruneFramesBefore(frames: ArrayDeque<AudioFrame>, timestampMs: Long) {
        while (frames.peekFirst()?.elapsedMs?.let { it < timestampMs } == true) {
            frames.removeFirst()
        }
    }

    private fun collectSamples(
        frames: Iterable<AudioFrame>,
        startMs: Long,
        endMs: Long
    ): FloatArray {
        if (endMs <= startMs) return FloatArray(0)
        val chunks = mutableListOf<FloatArray>()
        var totalSamples = 0
        for (frame in frames) {
            val frameStartMs = audioFrameStartMs(frame)
            val overlapStartMs = maxOf(startMs, frameStartMs)
            val overlapEndMs = minOf(endMs, frame.elapsedMs)
            if (overlapEndMs <= overlapStartMs) continue
            val from = ((overlapStartMs - frameStartMs) * ASR_SAMPLE_RATE / 1000L)
                .toInt()
                .coerceIn(0, frame.asrSamples.size)
            val to = ((overlapEndMs - frameStartMs) * ASR_SAMPLE_RATE / 1000L)
                .toInt()
                .coerceIn(from, frame.asrSamples.size)
            if (to > from) {
                frame.asrSamples.copyOfRange(from, to).also {
                    chunks += it
                    totalSamples += it.size
                }
            }
        }
        val result = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
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
            speakerFrames?.close()
            speakerFrames = null
            recognitionJob?.join()
            recognitionJob = null
            speakerJob?.join()
            speakerJob = null
            liveConversation = null
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
        audioFrames?.close()
        audioFrames = null
        speakerFrames?.close()
        speakerFrames = null
        liveConversation = null
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
        private const val EXTRA_SPEAKER_DIARIZATION_ENABLED = "speaker_diarization_enabled"
        private const val EXTRA_SPEAKER_MODEL_ID = "speaker_model_id"
        private const val EXTRA_PHOTO_PATH = "photo_path"
        const val EXTRA_OPEN_RECORDER = "open_recorder"
        private const val NOTIFICATION_CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_WAVEFORM_SAMPLES = 180
        private const val ASR_SAMPLE_RATE = 16_000
        private const val LIVE_REFRESH_INTERVAL_MS = 900L
        private const val MIN_PARTIAL_AUDIO_MS = 450L
        private const val MAX_PARTIAL_WINDOW_MS = 10_000L
        private const val MAX_LIVE_BUFFER_RETENTION_MS = 20_000L
        private const val SAFETY_COMMIT_AFTER_MS = 10_000L
        private const val SAFETY_COMMIT_WINDOW_MS = 8_000L
        private const val LIVE_VOICE_HOLD_MS = 850L
        private const val LIVE_VOICE_RMS_THRESHOLD = 0.006f
        private const val MIN_LIVE_RECOGNITION_SAMPLES = ASR_SAMPLE_RATE * 2 / 5
        private val _state = MutableStateFlow(RecordingServiceState())
        val state: StateFlow<RecordingServiceState> = _state.asStateFlow()

        fun start(
            context: Context,
            recordingId: String,
            language: RecordingLanguage,
            transcriptionMode: TranscriptionMode,
            modelId: LocalModelId,
            speakerDiarizationEnabled: Boolean,
            speakerModelId: LsEendModelId
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RECORDING_ID, recordingId)
                    putExtra(EXTRA_LANGUAGE, language.name)
                    putExtra(EXTRA_TRANSCRIPTION_MODE, transcriptionMode.name)
                    putExtra(EXTRA_MODEL_ID, modelId.name)
                    putExtra(EXTRA_SPEAKER_DIARIZATION_ENABLED, speakerDiarizationEnabled)
                    putExtra(EXTRA_SPEAKER_MODEL_ID, speakerModelId.name)
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
