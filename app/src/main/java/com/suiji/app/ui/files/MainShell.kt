package com.suiji.app.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suiji.app.R
import com.suiji.app.model.FileFilter
import com.suiji.app.model.MainTab
import com.suiji.app.model.SortOrder
import com.suiji.app.model.SuijiUiState
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.ThemeMode
import com.suiji.app.model.UiLanguage
import com.suiji.app.model.TranscriptionMode
import com.suiji.app.ui.settings.SettingsScreen

@Composable
fun MainShell(
    uiState: SuijiUiState,
    onTabSelected: (MainTab) -> Unit,
    onRecordClick: () -> Unit,
    onShowFilterSheet: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFileFilterSelected: (FileFilter) -> Unit,
    onSortOrderSelected: (SortOrder) -> Unit,
    onRecordingClick: (RecordingItem) -> Unit,
    onUiLanguageSelected: (UiLanguage) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onAiServiceClick: () -> Unit,
    onSpeechModelsClick: () -> Unit,
    onSpeakerModelsClick: () -> Unit,
    onTranscriptionModeSelected: (TranscriptionMode) -> Unit,
    onSpeakerDiarizationEnabledChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            SuijiBottomBar(
                selectedTab = uiState.mainTab,
                showRecordButton = uiState.mainTab == MainTab.FILES,
                onTabSelected = onTabSelected,
                onRecordClick = onRecordClick
            )
        }
    ) { contentPadding ->
        when (uiState.mainTab) {
            MainTab.FILES -> FilesScreen(
                uiState = uiState,
                contentPadding = contentPadding,
                onShowFilterSheet = onShowFilterSheet,
                onSearchQueryChange = onSearchQueryChange,
                onFileFilterSelected = onFileFilterSelected,
                onSortOrderSelected = onSortOrderSelected,
                onRecordingClick = onRecordingClick
            )

            MainTab.SETTINGS -> SettingsScreen(
                uiState = uiState,
                contentPadding = contentPadding,
                onUiLanguageSelected = onUiLanguageSelected,
                onThemeModeSelected = onThemeModeSelected,
                onAiServiceClick = onAiServiceClick,
                onSpeechModelsClick = onSpeechModelsClick,
                onSpeakerModelsClick = onSpeakerModelsClick,
                onTranscriptionModeSelected = onTranscriptionModeSelected,
                onSpeakerDiarizationEnabledChange = onSpeakerDiarizationEnabledChange,
                onCheckForUpdates = onCheckForUpdates
            )
        }
    }
}

@Composable
private fun SuijiBottomBar(
    selectedTab: MainTab,
    showRecordButton: Boolean,
    onTabSelected: (MainTab) -> Unit,
    onRecordClick: () -> Unit
) {
    Box(modifier = Modifier.height(94.dp)) {
        NavigationBar(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.BottomCenter),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = selectedTab == MainTab.FILES,
                onClick = { onTabSelected(MainTab.FILES) },
                icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                label = { Text(stringResource(R.string.files_tab)) }
            )
            NavigationBarItem(
                selected = selectedTab == MainTab.SETTINGS,
                onClick = { onTabSelected(MainTab.SETTINGS) },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.settings_tab)) }
            )
        }

        if (showRecordButton) {
            FloatingActionButton(
                onClick = onRecordClick,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-20).dp)
                    .size(74.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 3.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = stringResource(R.string.start_recording),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
