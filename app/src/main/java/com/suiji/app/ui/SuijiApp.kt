package com.suiji.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.suiji.app.R
import com.suiji.app.model.AppUpdateState
import com.suiji.app.model.AppUpdateStatus
import com.suiji.app.model.RootScreen
import com.suiji.app.model.SuijiUiState
import com.suiji.app.model.UiLanguage
import com.suiji.app.ui.files.MainShell
import com.suiji.app.ui.recording.RecordingScreen
import com.suiji.app.ui.recordingdetail.RecordingDetailScreen
import com.suiji.app.ui.settings.AiServiceSettingsScreen
import com.suiji.app.ui.settings.LocalModelSettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SuijiApp(
    uiState: SuijiUiState,
    viewModel: SuijiViewModel,
    onUiLanguageSelected: (UiLanguage) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var showMicrophoneExplanation by remember { mutableStateOf(false) }

    fun beginRecording() {
        if (!viewModel.startRecording()) {
            Toast.makeText(context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else Toast.makeText(context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (viewModel.canRequestPackageInstalls()) {
            viewModel.installDownloadedUpdate()
        }
    }

    fun requestUpdateInstallation() {
        if (viewModel.canRequestPackageInstalls()) {
            viewModel.installDownloadedUpdate()
        } else {
            unknownSourcesLauncher.launch(viewModel.unknownSourcesIntent())
        }
    }

    fun requestRecording() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            beginRecording()
        } else {
            showMicrophoneExplanation = true
        }
    }

    fun stopAndSaveRecording() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val title = resources.getString(R.string.recording_default_title, timestamp)
        if (viewModel.stopRecording(title)) {
            Toast.makeText(context, R.string.recording_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
        }
    }

    when (uiState.rootScreen) {
        RootScreen.MAIN -> MainShell(
            uiState = uiState,
            onTabSelected = viewModel::selectMainTab,
            onRecordClick = ::requestRecording,
            onShowFilterSheet = viewModel::showFilterSheet,
            onSearchQueryChange = viewModel::setSearchQuery,
            onFileFilterSelected = viewModel::selectFileFilter,
            onSortOrderSelected = viewModel::selectSortOrder,
            onRecordingClick = viewModel::openRecording,
            onUiLanguageSelected = { language ->
                viewModel.setUiLanguage(language)
                onUiLanguageSelected(language)
            },
            onThemeModeSelected = viewModel::setThemeMode,
            onAiServiceClick = viewModel::openAiServiceSettings,
            onSpeechModelsClick = viewModel::openSpeechModelSettings,
            onTranscriptionModeSelected = viewModel::setTranscriptionMode,
            onSpeakerDiarizationEnabledChange = viewModel::setSpeakerDiarizationEnabled,
            onCheckForUpdates = viewModel::checkForUpdates
        )

        RootScreen.RECORDER -> {
            BackHandler {
                context.findActivity()?.moveTaskToBack(true)
            }
            RecordingScreen(
                session = uiState.recordingSession,
                onLanguageSelected = viewModel::setRecordingLanguage,
                onPauseResume = viewModel::togglePause,
                onAddMarker = viewModel::addRecordingMarker,
                onPhotoCaptured = viewModel::onPhotoCaptured,
                onStop = ::stopAndSaveRecording
            )
        }

        RootScreen.RECORDING_DETAIL -> {
            val recording = uiState.selectedRecording
            if (recording == null) {
                viewModel.closeRecording()
            } else {
                BackHandler(onBack = viewModel::closeRecording)
                RecordingDetailScreen(
                    recording = recording,
                    onBack = viewModel::closeRecording,
                    onToggleFavorite = { viewModel.toggleFavorite(recording) },
                    onCategorySelected = { category ->
                        viewModel.setRecordingCategory(recording, category)
                    },
                    onDelete = { viewModel.deleteRecording(recording) },
                    isTranscribing = recording.id in uiState.transcribingIds,
                    onRetryTranscription = { viewModel.retryTranscription(recording) },
                    onRenameSpeaker = { speakerId, name ->
                        viewModel.renameSpeaker(recording, speakerId, name)
                    }
                )
            }
        }

        RootScreen.AI_SERVICE_SETTINGS -> {
            BackHandler(onBack = viewModel::closeAiServiceSettings)
            AiServiceSettingsScreen(
                config = uiState.aiServiceConfig,
                onBack = viewModel::closeAiServiceSettings,
                onSave = { config ->
                    viewModel.saveAiServiceConfig(config)
                    viewModel.closeAiServiceSettings()
                }
            )
        }

        RootScreen.SPEECH_MODEL_SETTINGS -> {
            BackHandler(onBack = viewModel::closeSpeechModelSettings)
            LocalModelSettingsScreen(
                models = uiState.localModels,
                selectedModelId = uiState.selectedLocalModelId,
                onBack = viewModel::closeSpeechModelSettings,
                onSelect = viewModel::selectLocalModel,
                onDownload = viewModel::downloadLocalModel,
                onCancelDownload = viewModel::cancelLocalModelDownload,
                onDelete = viewModel::deleteLocalModel
            )
        }
    }

    if (showMicrophoneExplanation) {
        AlertDialog(
            onDismissRequest = { showMicrophoneExplanation = false },
            title = { Text(text = stringResource(R.string.microphone_permission_title)) },
            text = { Text(text = stringResource(R.string.microphone_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showMicrophoneExplanation = false
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text(text = stringResource(R.string.grant_permission))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicrophoneExplanation = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    AppUpdateDialog(
        state = uiState.appUpdate,
        onDismiss = viewModel::dismissUpdateDialog,
        onDownload = viewModel::downloadUpdate,
        onInstall = ::requestUpdateInstallation,
        onRetry = viewModel::checkForUpdates
    )
}

@Composable
private fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    when (state.status) {
        AppUpdateStatus.IDLE,
        AppUpdateStatus.CHECKING -> Unit

        AppUpdateStatus.UP_TO_DATE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.check_for_updates)) },
            text = { Text(stringResource(R.string.app_is_up_to_date)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        )

        AppUpdateStatus.AVAILABLE -> {
            val info = state.info ?: return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(stringResource(R.string.update_available_title, info.versionName))
                },
                text = {
                    Text(
                        info.releaseNotes.ifBlank {
                            stringResource(R.string.update_release_notes_empty)
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.download_update))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        AppUpdateStatus.DOWNLOADING -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.downloading_update)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.downloading_update_percent,
                            (state.progress * 100).toInt()
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {}
        )

        AppUpdateStatus.READY_TO_INSTALL -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ready_to_install_title)) },
            text = { Text(stringResource(R.string.ready_to_install_body)) },
            confirmButton = {
                TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.install_update))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        )

        AppUpdateStatus.ERROR -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_failed)) },
            text = {
                Text(
                    stringResource(
                        R.string.update_failed_body,
                        state.errorMessage.orEmpty()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
