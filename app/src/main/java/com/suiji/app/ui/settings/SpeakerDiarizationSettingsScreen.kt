package com.suiji.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suiji.app.R
import com.suiji.app.model.LocalModelOperation
import com.suiji.app.model.SpeakerDiarizationModelId
import com.suiji.app.model.SpeakerDiarizationModelState
import java.util.Locale

@Composable
fun SpeakerDiarizationSettingsScreen(
    enabled: Boolean,
    models: List<SpeakerDiarizationModelState>,
    selectedModelId: SpeakerDiarizationModelId,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSelect: (SpeakerDiarizationModelId) -> Unit,
    onDownload: (SpeakerDiarizationModelId) -> Unit,
    onCancelDownload: (SpeakerDiarizationModelId) -> Unit,
    onDelete: (SpeakerDiarizationModelId) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                text = stringResource(R.string.speaker_diarization_settings),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.enable_speaker_diarization),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.enable_speaker_diarization_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            items(models, key = { it.descriptor.id }) { state ->
                val selected = state.descriptor.id == selectedModelId
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    state.descriptor.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.speaker_model_description),
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.operation == LocalModelOperation.INSTALLED) {
                                RadioButton(selected = selected, onClick = {
                                    onSelect(state.descriptor.id)
                                })
                            }
                        }
                        Text(
                            stringResource(
                                R.string.model_size_and_version,
                                formatSpeakerBytes(state.descriptor.installedBytes),
                                state.descriptor.version
                            ),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        when (state.operation) {
                            LocalModelOperation.NOT_INSTALLED -> {
                                Button(
                                    onClick = { onDownload(state.descriptor.id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.Download, contentDescription = null)
                                    Text(
                                        stringResource(R.string.download_model),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                            LocalModelOperation.DOWNLOADING -> {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.download_progress,
                                            formatSpeakerBytes(state.downloadedBytes),
                                            formatSpeakerBytes(state.totalBytes)
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(onClick = { onCancelDownload(state.descriptor.id) }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            }
                            LocalModelOperation.VERIFYING -> {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    stringResource(R.string.verifying_model),
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            LocalModelOperation.INSTALLED -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(
                                            if (selected) R.string.model_selected else R.string.model_installed
                                        )
                                    )
                                    IconButton(onClick = { onDelete(state.descriptor.id) }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.delete_local_model)
                                        )
                                    }
                                }
                            }
                            LocalModelOperation.FAILED -> {
                                Text(
                                    state.errorMessage ?: stringResource(R.string.model_install_failed),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = { onDownload(state.descriptor.id) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.speaker_model_module_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSpeakerBytes(bytes: Long): String =
    String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
