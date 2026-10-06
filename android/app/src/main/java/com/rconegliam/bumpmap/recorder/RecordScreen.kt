package com.rconegliam.bumpmap.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.ui.currentLocale
import kotlinx.coroutines.delay
import java.io.File

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun allGranted(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun RecordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by RecorderState.status.collectAsStateWithLifecycle()
    var recordings by remember { mutableStateOf(RecordingStore.list(context)) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(status.isRecording, status.finishedCount) {
        recordings = RecordingStore.list(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionDenied = !RecorderService.hasLocationPermission(context)
        if (!permissionDenied) RecorderService.start(context)
    }

    val visibleRecordings = recordings.filter { !status.isRecording || it.name != status.fileName }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.record_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.record_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item { StatusCard(status) }
        item {
            Button(
                onClick = {
                    when {
                        status.isRecording -> RecorderService.stop(context)
                        allGranted(context) -> RecorderService.start(context)
                        else -> permissionLauncher.launch(requiredPermissions())
                    }
                },
                colors = if (status.isRecording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (status.isRecording) R.string.record_stop else R.string.record_start))
            }
        }
        if (permissionDenied) {
            item {
                Text(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
            }
        }
        if (status.isRecording) {
            item { MarkButtons(onMark = { RecorderService.mark(context, it) }) }
        }
        item {
            Text(
                stringResource(R.string.recordings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (visibleRecordings.isEmpty()) {
            item { Text(stringResource(R.string.recordings_empty)) }
        } else {
            items(visibleRecordings, key = { it.name }) { file ->
                RecordingRow(
                    file = file,
                    onShare = { context.startActivity(RecordingStore.shareIntent(context, file)) },
                    onDelete = {
                        file.delete()
                        recordings = RecordingStore.list(context)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: RecorderStatus) {
    val locale = currentLocale()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.isRecording) {
        while (status.isRecording) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (status.isRecording) R.string.record_status_recording else R.string.record_status_idle),
                style = MaterialTheme.typography.titleMedium,
                color = if (status.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (status.isRecording) {
                val elapsedSeconds = ((now - status.startedAtMs) / 1000).coerceAtLeast(0)
                Text(stringResource(R.string.record_elapsed, DateUtils.formatElapsedTime(elapsedSeconds)))
                val speed = status.speedKmh
                Text(
                    if (speed == null) {
                        stringResource(R.string.record_speed_unknown)
                    } else {
                        stringResource(R.string.record_speed, String.format(locale, "%.0f", speed))
                    },
                )
                status.accuracyM?.let {
                    Text(stringResource(R.string.record_gps_accuracy, String.format(locale, "%.0f", it)))
                }
                Text(stringResource(R.string.record_shake, String.format(locale, "%.2f", status.shakeRms)))
                Text(stringResource(R.string.record_samples, status.sensorSamples))
                Text(pluralStringResource(R.plurals.record_marks, status.marks, status.marks))
            }
        }
    }
}

@Composable
private fun MarkButtons(onMark: (MarkType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mark_section), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                MarkType.SPEED_BUMP to R.string.mark_bump,
                MarkType.POTHOLE to R.string.mark_pothole,
                MarkType.ROUGH to R.string.mark_rough,
            ).forEach { (type, label) ->
                FilledTonalButton(onClick = { onMark(type) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(label), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(file: File, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val details = DateUtils.formatDateTime(
        context,
        file.lastModified(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
    ) + " · " + Formatter.formatShortFileSize(context, file.length())
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.recording_share))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.recording_delete))
            }
        }
    }
}
