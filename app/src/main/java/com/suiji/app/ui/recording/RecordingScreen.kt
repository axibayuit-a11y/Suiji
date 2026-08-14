package com.suiji.app.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.suiji.app.R
import com.suiji.app.model.LiveTranscriptionStatus
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.model.RecordingSessionState
import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import java.io.File
import java.util.Locale
import java.util.UUID

@Composable
fun RecordingScreen(
    session: RecordingSessionState,
    onLanguageSelected: (RecordingLanguage) -> Unit,
    onPauseResume: () -> Unit,
    onAddMarker: () -> Unit,
    onPhotoCaptured: (String) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var showCameraExplanation by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val photo = pendingPhoto
        pendingPhoto = null
        if (saved && photo != null) {
            onPhotoCaptured(photo.absolutePath)
        } else {
            photo?.delete()
            Toast.makeText(context, R.string.camera_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCamera() {
        try {
            val directory = File(context.filesDir, "recording_photos").apply { mkdirs() }
            val photo = File(directory, "photo-${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photo)
            pendingPhoto = photo
            takePictureLauncher.launch(uri)
        } catch (_: Exception) {
            pendingPhoto?.delete()
            pendingPhoto = null
            Toast.makeText(context, R.string.camera_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(context, R.string.camera_failed, Toast.LENGTH_SHORT).show()
    }

    fun requestPhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            showCameraExplanation = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatElapsed(session.elapsedMs),
            modifier = Modifier.padding(top = 22.dp),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Light
        )
        Text(
            text = stringResource(R.string.high_quality),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        Surface(
            modifier = Modifier
                .padding(top = 22.dp)
                .clickable(onClick = onAddMarker),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = if (session.markerTimestampsMs.isEmpty()) {
                        stringResource(R.string.add_marker)
                    } else {
                        stringResource(R.string.marker_added, session.markerTimestampsMs.size)
                    },
                    modifier = Modifier.padding(start = 7.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        RecordingWaveform(
            waveform = session.waveform,
            isPaused = session.isPaused,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(96.dp)
        )

        LiveTranscriptCard(
            session = session,
            languageMenuExpanded = languageMenuExpanded,
            onLanguageMenuExpandedChange = { languageMenuExpanded = it },
            onLanguageSelected = onLanguageSelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .weight(1f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RecorderAction(
                icon = Icons.Outlined.PhotoCamera,
                label = stringResource(R.string.camera),
                onClick = ::requestPhoto
            )

            Surface(
                modifier = Modifier
                    .size(78.dp)
                    .clickable(onClick = onStop),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.stop),
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.surface
                    )
                }
            }

            RecorderAction(
                icon = if (session.isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                label = if (session.isPaused) stringResource(R.string.resume)
                else stringResource(R.string.pause),
                onClick = onPauseResume
            )
        }
    }

    if (showCameraExplanation) {
        AlertDialog(
            onDismissRequest = { showCameraExplanation = false },
            title = { Text(stringResource(R.string.camera_permission_title)) },
            text = { Text(stringResource(R.string.camera_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showCameraExplanation = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text(stringResource(R.string.grant_permission)) }
            },
            dismissButton = {
                TextButton(onClick = { showCameraExplanation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun LiveTranscriptCard(
    session: RecordingSessionState,
    languageMenuExpanded: Boolean,
    onLanguageMenuExpandedChange: (Boolean) -> Unit,
    onLanguageSelected: (RecordingLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_recognition),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(
                        text = stringResource(R.string.live_transcription),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (session.timeline.isEmpty()) {
                Text(
                    text = liveTranscriptionMessage(session.liveTranscriptionStatus),
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = session.timeline.sortedBy(TimelineEvent::timestampMs),
                        key = TimelineEvent::id
                    ) { event ->
                        LiveTimelineEvent(event)
                    }
                }
            }

            Box {
                Surface(
                    modifier = Modifier.clickable { onLanguageMenuExpandedChange(true) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(recordingLanguageLabel(session.language))
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(R.string.recording_language),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(17.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { onLanguageMenuExpandedChange(false) }
                ) {
                    RecordingLanguage.entries.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(recordingLanguageLabel(language)) },
                            onClick = {
                                onLanguageSelected(language)
                                onLanguageMenuExpandedChange(false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTimelineEvent(event: TimelineEvent) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatTimelineTime(event.timestampMs),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            when (event.type) {
                TimelineEventType.SPEECH -> {
                    event.speakerId?.let { speakerId ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Text(
                                text = speakerIdToLabel(speakerId),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Text(
                        text = event.text,
                        modifier = Modifier.padding(top = if (event.speakerId == null) 0.dp else 6.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                TimelineEventType.PHOTO -> {
                    val bitmap = remember(event.photoPath) {
                        event.photoPath?.let { path ->
                            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.timeline_photo),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(stringResource(R.string.timeline_photo))
                    }
                }

                TimelineEventType.MARKER -> Text(
                    text = stringResource(R.string.timeline_marker),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun speakerIdToLabel(speakerId: String): String {
    val number = speakerId.substringAfterLast('_').toIntOrNull()?.plus(1)
    return if (number != null) stringResource(R.string.speaker_number, number) else speakerId
}

private fun formatTimelineTime(timestampMs: Long): String {
    val totalSeconds = timestampMs.coerceAtLeast(0L) / 1000
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun liveTranscriptionMessage(status: LiveTranscriptionStatus): String = when (status) {
    LiveTranscriptionStatus.DISABLED -> stringResource(R.string.live_disabled)
    LiveTranscriptionStatus.MODEL_REQUIRED -> stringResource(R.string.live_model_required)
    LiveTranscriptionStatus.CLOUD_AFTER_RECORDING -> stringResource(R.string.live_cloud_after_recording)
    LiveTranscriptionStatus.INITIALIZING -> stringResource(R.string.live_initializing)
    LiveTranscriptionStatus.LISTENING -> stringResource(R.string.live_listening)
    LiveTranscriptionStatus.RECOGNIZING -> stringResource(R.string.live_recognizing)
    LiveTranscriptionStatus.ERROR -> stringResource(R.string.live_error)
}

@Composable
private fun RecordingWaveform(
    waveform: List<Float>,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.onSurface
    val futureColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val centerY = size.height / 2f
        val cursorX = size.width * 0.62f
        val historyBars = 48
        val historyGap = cursorX / historyBars
        val values = waveform.takeLast(historyBars)
        repeat(historyBars) { index ->
            val valueIndex = values.size - historyBars + index
            val value = if (valueIndex >= 0) values[valueIndex] else 0.02f
            val halfHeight = size.height * value.coerceIn(0.02f, 1f) * 0.42f
            val x = historyGap * index + historyGap / 2f
            drawLine(
                color = if (isPaused) futureColor else activeColor,
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val futureBars = 26
        val futureGap = (size.width - cursorX) / futureBars
        repeat(futureBars) { index ->
            val x = cursorX + futureGap * index + futureGap / 2f
            drawLine(
                color = futureColor,
                start = Offset(x, centerY - 4.dp.toPx()),
                end = Offset(x, centerY + 4.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawLine(
            color = activeColor,
            start = Offset(cursorX, 6.dp.toPx()),
            end = Offset(cursorX, size.height - 6.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(activeColor, radius = 4.dp.toPx(), center = Offset(cursorX, size.height - 6.dp.toPx()))
    }
}

@Composable
private fun RecorderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
            }
        }
        Text(text = label, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun recordingLanguageLabel(language: RecordingLanguage): String = when (language) {
    RecordingLanguage.CHINESE -> stringResource(R.string.language_chinese)
    RecordingLanguage.ENGLISH -> stringResource(R.string.language_english)
    RecordingLanguage.CANTONESE_HK -> stringResource(R.string.language_cantonese)
}

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (elapsedMs % 1000) / 10
    return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
}
