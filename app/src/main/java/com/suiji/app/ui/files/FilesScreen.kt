package com.suiji.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suiji.app.R
import com.suiji.app.model.FileFilter
import com.suiji.app.model.RecordingCategory
import com.suiji.app.model.RecordingItem
import com.suiji.app.model.SortOrder
import com.suiji.app.model.SuijiUiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    uiState: SuijiUiState,
    contentPadding: PaddingValues,
    onShowFilterSheet: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFileFilterSelected: (FileFilter) -> Unit,
    onSortOrderSelected: (SortOrder) -> Unit,
    onRecordingClick: (RecordingItem) -> Unit
) {
    val visibleRecordings = uiState.recordings
        .filter { recording ->
            when (uiState.fileFilter) {
                FileFilter.ALL -> true
                FileFilter.UNCATEGORIZED -> recording.category == RecordingCategory.UNCLASSIFIED
                FileFilter.FAVORITES -> recording.isFavorite
                FileFilter.MEETING -> recording.category == RecordingCategory.MEETING
                FileFilter.CLASS -> recording.category == RecordingCategory.CLASS
            }
        }
        .filter { recording ->
            uiState.searchQuery.isBlank() ||
                recording.title.contains(uiState.searchQuery, ignoreCase = true) ||
                recording.preview.contains(uiState.searchQuery, ignoreCase = true)
        }
        .let { recordings ->
            when (uiState.sortOrder) {
                SortOrder.NEWEST -> recordings.sortedByDescending { it.createdAt }
                SortOrder.OLDEST -> recordings.sortedBy { it.createdAt }
                SortOrder.LONGEST -> recordings.sortedByDescending { it.durationMs }
            }
        }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 36.dp)
    ) {
        item {
            FilterTitle(
                filter = uiState.fileFilter,
                count = visibleRecordings.size,
                onClick = { onShowFilterSheet(true) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.search_recordings)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = stringResource(R.string.clear_search)
                            )
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        if (visibleRecordings.isEmpty()) {
            item { EmptyFilesState() }
        } else {
            items(visibleRecordings, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    onClick = { onRecordingClick(recording) }
                )
            }
        }
    }

    if (uiState.showFilterSheet) {
        FilterSortSheet(
            selectedFilter = uiState.fileFilter,
            selectedSort = uiState.sortOrder,
            onDismiss = { onShowFilterSheet(false) },
            onFilterSelected = onFileFilterSelected,
            onSortSelected = onSortOrderSelected
        )
    }
}

@Composable
private fun FilterTitle(
    filter: FileFilter,
    count: Int,
    onClick: () -> Unit
) {
    val title = when (filter) {
        FileFilter.ALL -> stringResource(R.string.all_files)
        FileFilter.UNCATEGORIZED -> stringResource(R.string.uncategorized)
        FileFilter.FAVORITES -> stringResource(R.string.favorites)
        FileFilter.MEETING -> stringResource(R.string.category_meeting)
        FileFilter.CLASS -> stringResource(R.string.category_class)
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.filter_sort),
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = stringResource(R.string.recording_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingItem,
    onClick: () -> Unit
) {
    val playDescription = stringResource(R.string.play_recording, recording.title)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .semantics { contentDescription = playDescription },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recording.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (recording.isFavorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.favorites),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT
                        ).format(Date(recording.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDuration(recording.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (recording.category != RecordingCategory.UNCLASSIFIED) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = when (recording.category) {
                            RecordingCategory.MEETING -> stringResource(R.string.category_meeting)
                            RecordingCategory.CLASS -> stringResource(R.string.category_class)
                            RecordingCategory.UNCLASSIFIED -> stringResource(R.string.uncategorized)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (recording.preview.isNotBlank()) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = recording.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (recording.photoPaths.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = stringResource(R.string.photo_attached, recording.photoPaths.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EmptyFilesState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.no_recordings), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.no_recordings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSortSheet(
    selectedFilter: FileFilter,
    selectedSort: SortOrder,
    onDismiss: () -> Unit,
    onFilterSelected: (FileFilter) -> Unit,
    onSortSelected: (SortOrder) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 34.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.filter_sort),
                    style = MaterialTheme.typography.headlineMedium
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.sort_by),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            SortOrder.entries.forEach { order ->
                SelectionRow(
                    label = when (order) {
                        SortOrder.NEWEST -> stringResource(R.string.newest_first)
                        SortOrder.OLDEST -> stringResource(R.string.oldest_first)
                        SortOrder.LONGEST -> stringResource(R.string.longest_first)
                    },
                    selected = selectedSort == order,
                    onClick = { onSortSelected(order) }
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.category),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            FileFilter.entries.forEach { filter ->
                SelectionRow(
                    label = when (filter) {
                        FileFilter.ALL -> stringResource(R.string.all_files)
                        FileFilter.UNCATEGORIZED -> stringResource(R.string.uncategorized)
                        FileFilter.FAVORITES -> stringResource(R.string.favorites)
                        FileFilter.MEETING -> stringResource(R.string.category_meeting)
                        FileFilter.CLASS -> stringResource(R.string.category_class)
                    },
                    selected = selectedFilter == filter,
                    onClick = { onFilterSelected(filter) }
                )
            }
        }
    }
}

@Composable
private fun SelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
