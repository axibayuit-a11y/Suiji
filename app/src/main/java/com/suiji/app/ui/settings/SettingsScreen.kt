package com.suiji.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suiji.app.R
import com.suiji.app.BuildConfig
import com.suiji.app.model.AppUpdateStatus
import com.suiji.app.model.SuijiUiState
import com.suiji.app.model.ThemeMode
import com.suiji.app.model.UiLanguage
import com.suiji.app.model.TranscriptionMode
import com.suiji.app.model.LocalModelOperation

@Composable
fun SettingsScreen(
    uiState: SuijiUiState,
    contentPadding: PaddingValues,
    onUiLanguageSelected: (UiLanguage) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onAiServiceClick: () -> Unit,
    onSpeechModelsClick: () -> Unit,
    onTranscriptionModeSelected: (TranscriptionMode) -> Unit,
    onSpeakerDiarizationEnabledChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = contentPadding.calculateTopPadding() + 28.dp,
            end = 24.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp
        )
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(36.dp))
        }

        item { SectionTitle(R.string.interface_language) }
        item {
            SelectionRow(Icons.Outlined.Language, R.string.simplified_chinese,
                uiState.uiLanguage == UiLanguage.SIMPLIFIED_CHINESE
            ) { onUiLanguageSelected(UiLanguage.SIMPLIFIED_CHINESE) }
        }
        item {
            SelectionRow(Icons.Outlined.Language, R.string.english,
                uiState.uiLanguage == UiLanguage.ENGLISH
            ) { onUiLanguageSelected(UiLanguage.ENGLISH) }
        }
        item {
            SelectionRow(Icons.Outlined.Language, R.string.traditional_chinese,
                uiState.uiLanguage == UiLanguage.TRADITIONAL_CHINESE
            ) { onUiLanguageSelected(UiLanguage.TRADITIONAL_CHINESE) }
        }

        item { SectionTitle(R.string.appearance) }
        item {
            SelectionRow(Icons.Outlined.DarkMode, R.string.theme_system,
                uiState.themeMode == ThemeMode.SYSTEM
            ) { onThemeModeSelected(ThemeMode.SYSTEM) }
        }
        item {
            SelectionRow(Icons.Outlined.DarkMode, R.string.theme_light,
                uiState.themeMode == ThemeMode.LIGHT
            ) { onThemeModeSelected(ThemeMode.LIGHT) }
        }
        item {
            SelectionRow(Icons.Outlined.DarkMode, R.string.theme_dark,
                uiState.themeMode == ThemeMode.DARK
            ) { onThemeModeSelected(ThemeMode.DARK) }
        }

        item { SectionTitle(R.string.speech_and_models) }
        item {
            SelectionRow(Icons.Outlined.Memory, R.string.transcription_off,
                uiState.transcriptionMode == TranscriptionMode.OFF
            ) { onTranscriptionModeSelected(TranscriptionMode.OFF) }
        }
        item {
            SelectionRow(Icons.Outlined.Memory, R.string.transcription_local,
                uiState.transcriptionMode == TranscriptionMode.LOCAL
            ) { onTranscriptionModeSelected(TranscriptionMode.LOCAL) }
        }
        item {
            val selectedModel = uiState.localModels.firstOrNull {
                it.descriptor.id == uiState.selectedLocalModelId
            }
            InformationRow(
                Icons.Outlined.Memory,
                R.string.local_model,
                if (selectedModel?.operation == LocalModelOperation.INSTALLED) {
                    selectedModel.descriptor.displayName
                } else {
                    stringResource(R.string.not_downloaded)
                },
                onClick = onSpeechModelsClick
            )
        }

        item { SectionTitle(R.string.speaker_diarization_section) }
        item {
            SwitchRow(
                icon = Icons.Outlined.Groups,
                title = R.string.enable_speaker_diarization,
                description = stringResource(R.string.enable_speaker_diarization_hint),
                checked = uiState.speakerDiarizationEnabled,
                onCheckedChange = onSpeakerDiarizationEnabledChange
            )
        }
        item {
            InformationRow(
                Icons.Outlined.Groups,
                R.string.speaker_diarization_model,
                stringResource(R.string.lseend_model_summary),
                showChevron = false
            )
        }

        item { SectionTitle(R.string.ai_features) }
        item {
            InformationRow(
                Icons.Outlined.CloudQueue,
                R.string.ai_service,
                stringResource(
                    if (uiState.aiServiceConfig.isReady) {
                        R.string.ai_service_configured
                    } else {
                        R.string.not_configured
                    }
                ),
                onClick = onAiServiceClick
            )
        }

        item { SectionTitle(R.string.storage) }
        item {
            InformationRow(
                Icons.Outlined.Storage,
                R.string.storage,
                stringResource(R.string.storage_description),
                showChevron = false
            )
        }

        item { SectionTitle(R.string.about) }
        item {
            InformationRow(
                Icons.Outlined.Info,
                R.string.about,
                stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                showChevron = false
            )
        }
        item {
            val isBusy = uiState.appUpdate.status == AppUpdateStatus.CHECKING ||
                uiState.appUpdate.status == AppUpdateStatus.DOWNLOADING
            val updateDescription = when (uiState.appUpdate.status) {
                AppUpdateStatus.CHECKING -> stringResource(R.string.checking_for_updates)
                AppUpdateStatus.DOWNLOADING -> stringResource(
                    R.string.downloading_update_percent,
                    (uiState.appUpdate.progress * 100).toInt()
                )
                else -> stringResource(R.string.check_updates_description)
            }
            InformationRow(
                Icons.Outlined.SystemUpdateAlt,
                R.string.check_for_updates,
                updateDescription,
                showChevron = !isBusy,
                onClick = if (isBusy) null else onCheckForUpdates
            )
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    @StringRes title: Int,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 12.dp)
        ) {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SelectionRow(
    icon: ImageVector,
    @StringRes title: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(
            text = stringResource(title),
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        RadioButton(selected = selected, onClick = onClick)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun InformationRow(
    icon: ImageVector,
    @StringRes title: Int,
    description: String,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showChevron) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
