package com.suiji.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.suiji.app.model.LsEendModelId
import com.suiji.app.model.LsEendModelState
import com.suiji.app.model.ModelOperation
import java.util.Locale

@Composable
fun SpeakerModelSettingsScreen(
    models: List<LsEendModelState>,
    selectedModelId: LsEendModelId,
    onBack: () -> Unit,
    onSelect: (LsEendModelId) -> Unit,
    onDownload: (LsEendModelId) -> Unit,
    onCancelDownload: (LsEendModelId) -> Unit,
    onDelete: (LsEendModelId) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<LsEendModelId?>(null) }

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
                text = stringResource(R.string.speaker_model_center),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp, 16.dp, 24.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.speaker_model_center_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(models, key = { it.descriptor.id }) { model ->
                SpeakerModelCard(
                    state = model,
                    selected = selectedModelId == model.descriptor.id,
                    onSelect = { onSelect(model.descriptor.id) },
                    onDownload = { onDownload(model.descriptor.id) },
                    onCancelDownload = { onCancelDownload(model.descriptor.id) },
                    onDelete = { pendingDelete = model.descriptor.id }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.speaker_model_source_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingDelete?.let { modelId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_speaker_model_title)) },
            text = { Text(stringResource(R.string.delete_speaker_model_body)) },
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
private fun SpeakerModelCard(
    state: LsEendModelState,
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
                enabled = state.operation == ModelOperation.INSTALLED,
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
                            R.string.lseend_model_description,
                            descriptor.maxSpeakers
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.operation == ModelOperation.INSTALLED) {
                    RadioButton(selected = selected, onClick = onSelect)
                }
            }

            Text(
                text = stringResource(
                    R.string.model_size_and_version,
                    formatSpeakerModelBytes(descriptor.modelBytes),
                    descriptor.version
                ),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            when (state.operation) {
                ModelOperation.NOT_INSTALLED -> Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text(
                        stringResource(R.string.download_model),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                ModelOperation.DOWNLOADING -> {
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
                                formatSpeakerModelBytes(state.downloadedBytes),
                                formatSpeakerModelBytes(state.totalBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onCancelDownload) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }

                ModelOperation.VERIFYING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.verifying_model),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                ModelOperation.INSTALLED -> Row(
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
                            contentDescription = stringResource(R.string.delete_speaker_model)
                        )
                    }
                }

                ModelOperation.FAILED -> {
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

private fun formatSpeakerModelBytes(bytes: Long): String =
    String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
