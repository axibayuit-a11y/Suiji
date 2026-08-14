package com.suiji.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suiji.app.R
import com.suiji.app.model.LocalModelId
import com.suiji.app.model.LocalModelKind
import com.suiji.app.model.LocalModelOperation
import com.suiji.app.model.LocalModelState

@Composable
fun LocalModelSettingsScreen(
    models: List<LocalModelState>,
    selectedModelId: LocalModelId,
    onBack: () -> Unit,
    onSelect: (LocalModelId) -> Unit,
    onDownload: (LocalModelId) -> Unit,
    onCancelDownload: (LocalModelId) -> Unit,
    onDelete: (LocalModelId) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<LocalModelId?>(null) }

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
                text = stringResource(R.string.local_model_center),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                top = 16.dp,
                end = 24.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.local_model_center_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(models, key = { it.descriptor.id }) { model ->
                LocalModelCard(
                    state = model,
                    selected = model.descriptor.kind == LocalModelKind.SPEECH_RECOGNITION &&
                        selectedModelId == model.descriptor.id,
                    onSelect = { onSelect(model.descriptor.id) },
                    onDownload = { onDownload(model.descriptor.id) },
                    onCancelDownload = { onCancelDownload(model.descriptor.id) },
                    onDelete = { pendingDelete = model.descriptor.id }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.local_model_source_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingDelete?.let { modelId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_local_model_title)) },
            text = { Text(stringResource(R.string.delete_local_model_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(modelId)
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun LocalModelCard(
    state: LocalModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val descriptor = state.descriptor
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = state.operation == LocalModelOperation.INSTALLED,
                onClick = onSelect
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        descriptor.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            if (descriptor.kind == LocalModelKind.SPEAKER_DIARIZATION) {
                                R.string.local_model_diarization_description
                            } else if (descriptor.id == LocalModelId.SENSEVOICE_CANTONESE) {
                                R.string.local_model_cantonese_description
                            } else {
                                R.string.local_model_general_description
                            }
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (
                    state.operation == LocalModelOperation.INSTALLED &&
                    descriptor.kind == LocalModelKind.SPEECH_RECOGNITION
                ) {
                    RadioButton(selected = selected, onClick = onSelect)
                }
            }

            Text(
                text = stringResource(
                    R.string.model_size_and_version,
                    formatBytes(descriptor.installedBytes),
                    descriptor.version
                ),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            when (state.operation) {
                LocalModelOperation.NOT_INSTALLED -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(
                                R.string.download_progress,
                                formatBytes(state.downloadedBytes),
                                formatBytes(state.totalBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onCancelDownload) {
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
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete_local_model)
                            )
                        }
                    }
                }

                LocalModelOperation.FAILED -> {
                    Text(
                        text = state.errorMessage ?: stringResource(R.string.model_install_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedButton(
                        onClick = onDownload,
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

private fun formatBytes(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024) {
        String.format(java.util.Locale.US, "%.1f GB", megabytes / 1024.0)
    } else {
        String.format(java.util.Locale.US, "%.0f MB", megabytes)
    }
}
