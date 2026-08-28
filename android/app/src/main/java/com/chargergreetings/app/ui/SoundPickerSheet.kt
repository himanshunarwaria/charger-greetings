package com.chargergreetings.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chargergreetings.app.audio.SoundCatalog
import com.chargergreetings.app.core.SoundSlot

private enum class PickerTab { BUILT_IN, FILE, RECORD }

/**
 * One sheet used by all three sound slots.
 *
 * Writing this once rather than three times is the whole reason [SoundSlot]
 * exists: the picker does not know or care which event it is configuring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPickerSheet(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onChooseBuiltIn: (String) -> Unit,
    onBrowseFiles: () -> Unit,
    onChooseRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit,
    onPreviewRecording: (String) -> Unit,
    onStartRecording: () -> Unit,
    onPauseResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopPreview: () -> Unit
) {
    val slot = state.picker.slot ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(PickerTab.BUILT_IN) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Sound for ${slot.displayName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == PickerTab.BUILT_IN,
                    onClick = { tab = PickerTab.BUILT_IN },
                    label = { Text("Built-in") }
                )
                FilterChip(
                    selected = tab == PickerTab.FILE,
                    onClick = { tab = PickerTab.FILE },
                    label = { Text("From phone") }
                )
                FilterChip(
                    selected = tab == PickerTab.RECORD,
                    onClick = { tab = PickerTab.RECORD },
                    label = { Text("Record") }
                )
            }

            Spacer(Modifier.height(16.dp))

            when (tab) {
                PickerTab.BUILT_IN -> BuiltInList(onChooseBuiltIn)
                PickerTab.FILE -> FileTab(onBrowseFiles)
                PickerTab.RECORD -> RecordTab(
                    state = state,
                    onChooseRecording = onChooseRecording,
                    onDeleteRecording = onDeleteRecording,
                    onPreviewRecording = onPreviewRecording,
                    onStartRecording = onStartRecording,
                    onPauseResumeRecording = onPauseResumeRecording,
                    onStopRecording = onStopRecording,
                    onSaveRecording = onSaveRecording,
                    onCancelRecording = onCancelRecording,
                    onStopPreview = onStopPreview
                )
            }
        }
    }
}

@Composable
private fun BuiltInList(onChoose: (String) -> Unit) {
    Column {
        SoundCatalog.builtIns.forEach { sound ->
            Text(
                sound.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(sound.id) }
                    .padding(vertical = 14.dp)
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FileTab(onBrowse: () -> Unit) {
    Column {
        Text(
            "Pick any audio file on your phone. MP3, WAV, M4A, AAC and OGG all " +
                "work where Android supports them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose from phone")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "The app keeps long-term permission for the file you pick, so it " +
                "still works after a restart. If you later move or delete it, " +
                "the built-in sound is used instead and this screen will say so.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordTab(
    state: SettingsUiState,
    onChooseRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit,
    onPreviewRecording: (String) -> Unit,
    onStartRecording: () -> Unit,
    onPauseResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopPreview: () -> Unit
) {
    val rec = state.recorder

    Column {
        Text(
            "Record a short message in your own voice. The microphone is only " +
                "used while you are on this screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        rec.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            // A finished take, waiting to be kept or thrown away.
            rec.pendingFileName != null -> {
                Text("Recording ready", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onPreviewRecording(rec.pendingFileName) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Preview") }
                    OutlinedButton(onClick = onStopPreview, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSaveRecording, modifier = Modifier.weight(1f)) {
                        Text("Use this")
                    }
                    OutlinedButton(onClick = onStartRecording, modifier = Modifier.weight(1f)) {
                        Text("Re-record")
                    }
                    OutlinedButton(onClick = onCancelRecording, modifier = Modifier.weight(1f)) {
                        Text("Discard")
                    }
                }
            }

            rec.recording -> {
                Text(
                    if (rec.paused) "Paused" else "Recording…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPauseResumeRecording, modifier = Modifier.weight(1f)) {
                        Text(if (rec.paused) "Resume" else "Pause")
                    }
                    Button(onClick = onStopRecording, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                    OutlinedButton(onClick = onCancelRecording, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                }
            }

            else -> {
                Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start recording")
                }
            }
        }

        if (state.picker.savedRecordings.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Saved recordings", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            state.picker.savedRecordings.forEach { name ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        friendlyRecordingName(name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).clickable { onChooseRecording(name) }
                    )
                    IconButton(onClick = { onPreviewRecording(name) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Preview")
                    }
                    IconButton(onClick = { onDeleteRecording(name) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onStopPreview) { Text("Stop preview") }
        }
    }
}

/** Turns "voice_1712345678901.m4a" into something a person can read. */
private fun friendlyRecordingName(fileName: String): String {
    val stamp = fileName.removePrefix("voice_").substringBefore('.').toLongOrNull()
        ?: return fileName
    return "Recording " + java.text.SimpleDateFormat(
        "d MMM, HH:mm", java.util.Locale.getDefault()
    ).format(java.util.Date(stamp))
}
