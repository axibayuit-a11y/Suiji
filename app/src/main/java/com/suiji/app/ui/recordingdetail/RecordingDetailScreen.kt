package com.suiji.app.ui.recordingdetail

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suiji.app.R
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.RecordingCategory
import com.suiji.app.model.TimelineEvent
import com.suiji.app.model.TimelineEventType
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingDetailScreen(
    recording: RecordingItem,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCategorySelected: (RecordingCategory) -> Unit,
    onDelete: () -> Unit,
    isTranscribing: Boolean,
    onRetryTranscription: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingSpeakerId by remember { mutableStateOf<String?>(null) }
    var speakerNameDraft by remember { mutableStateOf("") }
    val audioFile = remember(recording.audioPath) { File(recording.audioPath) }
    val mediaPlayer = remember(recording.id, recording.audioPath) {
        if (audioFile.isFile) {
            runCatching {
                MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    prepare()
                }
            }.getOrNull()
        } else {
            null
        }
    }
    var isPlaying by remember(recording.id) { mutableStateOf(false) }
    var positionMs by remember(recording.id) { mutableLongStateOf(0L) }
    val durationMs = remember(mediaPlayer, recording.durationMs) {
        (mediaPlayer?.duration?.toLong() ?: recording.durationMs).coerceAtLeast(1L)
    }

    DisposableEffect(mediaPlayer) {
        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0L
            runCatching { it.seekTo(0) }
        }
        onDispose {
            runCatching { mediaPlayer?.stop() }
            mediaPlayer?.release()
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying && mediaPlayer != null) {
            positionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(positionMs)
            delay(200)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(
                text = stringResource(R.string.recording_details),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (recording.isFavorite) {
                        Icons.Filled.Star
                    } else {
                        Icons.Outlined.StarBorder
                    },
                    contentDescription = stringResource(
                        if (recording.isFavorite) R.string.remove_favorite else R.string.mark_favorite
                    )
                )
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.delete_recording)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                top = 20.dp,
                end = 24.dp,
                bottom = 30.dp
            )
        ) {
            item {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(18.dp))
                MetadataRow(recording)
                Spacer(Modifier.height(24.dp))
                CategorySelector(
                    selected = recording.category,
                    onSelected = onCategorySelected
                )
                Spacer(Modifier.height(32.dp))
            }

            item {
                SectionHeader(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.complete_timeline)
                )
                Spacer(Modifier.height(10.dp))
                when {
                    isTranscribing -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                Text(
                                    text = stringResource(R.string.transcribing),
                                    modifier = Modifier.padding(start = 14.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    recording.transcriptionError != null -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = stringResource(R.string.transcription_failed),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = recording.transcriptionError,
                                    modifier = Modifier.padding(top = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = onRetryTranscription,
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }

                    recording.timeline.isEmpty() -> {
                        if (recording.transcript.isNotBlank()) {
                            Text(recording.transcript, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            PlaceholderCard(text = stringResource(R.string.transcript_pending))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            items(
                items = recording.timeline.sortedBy(TimelineEvent::timestampMs),
                key = TimelineEvent::id
            ) { event ->
                TimelineDetailEvent(
                    event = event,
                    speakerName = event.speakerId?.let { recording.speakerNames[it] },
                    onSeek = { timestamp ->
                        positionMs = timestamp.coerceIn(0L, durationMs)
                        mediaPlayer?.seekTo(positionMs.toInt())
                    },
                    onRenameSpeaker = { speakerId ->
                        pendingSpeakerId = speakerId
                        speakerNameDraft = recording.speakerNames[speakerId].orEmpty()
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            item {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(28.dp))
                SectionHeader(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.ai_summary)
                )
                Spacer(Modifier.height(10.dp))
                PlaceholderCard(text = stringResource(R.string.summary_pending))
            }
        }

        PlayerPanel(
            audioAvailable = mediaPlayer != null,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            onPlayPause = {
                mediaPlayer?.let { player ->
                    if (isPlaying) player.pause() else player.start()
                    isPlaying = !isPlaying
                }
            },
            onSeek = { requestedPosition ->
                positionMs = requestedPosition
                mediaPlayer?.seekTo(requestedPosition.toInt())
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_recording_title)) },
            text = { Text(stringResource(R.string.delete_recording_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                    Toast.makeText(context, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingSpeakerId?.let { speakerId ->
        AlertDialog(
            onDismissRequest = { pendingSpeakerId = null },
            title = { Text(stringResource(R.string.rename_speaker)) },
            text = {
                OutlinedTextField(
                    value = speakerNameDraft,
                    onValueChange = { speakerNameDraft = it },
                    label = { Text(stringResource(R.string.speaker_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSpeaker(speakerId, speakerNameDraft)
                    pendingSpeakerId = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSpeakerId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TimelineDetailEvent(
    event: TimelineEvent,
    speakerName: String?,
    onSeek: (Long) -> Unit,
    onRenameSpeaker: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Text(
                text = formatTimestamp(event.timestampMs),
                modifier = Modifier.clickable { onSeek(event.timestampMs) },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                when (event.type) {
                    TimelineEventType.SPEECH -> {
                        event.speakerId?.let { speakerId ->
                            Surface(
                                modifier = Modifier.clickable { onRenameSpeaker(speakerId) },
                                shape = RoundedCornerShape(12.dp),
                                color = speakerTagColor(speakerId)
                            ) {
                                Text(
                                    text = speakerName?.takeIf(String::isNotBlank)
                                        ?: defaultSpeakerName(speakerId),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF202020)
                                )
                            }
                        }
                        Text(
                            text = event.text,
                            modifier = Modifier.padding(top = if (event.speakerId == null) 0.dp else 8.dp),
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
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(stringResource(R.string.timeline_photo))
                        }
                    }

                    TimelineEventType.MARKER -> Text(
                        text = stringResource(R.string.timeline_marker),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun defaultSpeakerName(speakerId: String): String {
    val index = speakerId.substringAfterLast('_').toIntOrNull()?.plus(1)
    return if (index != null) stringResource(R.string.speaker_number, index) else speakerId
}

private fun speakerTagColor(speakerId: String): Color = when (
    speakerId.substringAfterLast('_').toIntOrNull()?.mod(4)
) {
    0 -> Color(0xFFFFD9DE)
    1 -> Color(0xFFE5E0FF)
    2 -> Color(0xFFFFE6BF)
    else -> Color(0xFFD9EAFE)
}

private fun formatTimestamp(timestampMs: Long): String {
    val seconds = timestampMs.coerceAtLeast(0L) / 1000
    return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun CategorySelector(
    selected: RecordingCategory,
    onSelected: (RecordingCategory) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.category),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecordingCategory.entries.forEach { category ->
                FilterChip(
                    selected = selected == category,
                    onClick = { onSelected(category) },
                    label = {
                        Text(
                            when (category) {
                                RecordingCategory.UNCLASSIFIED -> stringResource(R.string.uncategorized)
                                RecordingCategory.MEETING -> stringResource(R.string.category_meeting)
                                RecordingCategory.CLASS -> stringResource(R.string.category_class)
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(recording: RecordingItem) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetadataLine(
            label = stringResource(R.string.created_at),
            value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(recording.createdAt))
        )
        MetadataLine(
            label = stringResource(R.string.duration),
            value = formatDuration(recording.durationMs)
        )
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PhotoStrip(photoPaths: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(photoPaths, key = { it }) { path ->
            val bitmap = remember(path) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 176.dp, height = 124.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 176.dp, height = 124.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
        Text(
            text = title,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PlaceholderCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlayerPanel(
    audioAvailable: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!audioAvailable) {
                Text(
                    text = stringResource(R.string.audio_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = positionMs.coerceIn(0L, durationMs).toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..durationMs.toFloat(),
                enabled = audioAvailable,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDuration(positionMs), style = MaterialTheme.typography.labelMedium)
                Surface(
                    modifier = Modifier
                        .size(58.dp)
                        .clickable(enabled = audioAvailable, onClick = onPlayPause),
                    shape = CircleShape,
                    color = if (audioAvailable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(R.string.play_recording, ""),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.surface
                        )
                    }
                }
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
