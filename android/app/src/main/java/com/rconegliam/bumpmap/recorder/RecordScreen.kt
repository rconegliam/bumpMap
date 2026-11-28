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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.ExistingWorkPolicy
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.detection.labelRes
import com.rconegliam.bumpmap.sync.RecordingSync
import com.rconegliam.bumpmap.sync.SyncUiState
import com.rconegliam.bumpmap.sync.rememberSyncState
import com.rconegliam.bumpmap.ui.currentLocale
import kotlinx.coroutines.delay

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun allGranted(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun RecordScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by RecorderState.status.collectAsStateWithLifecycle()
    val sync = rememberSyncState()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionDenied = !RecorderService.hasLocationPermission(context)
        if (!permissionDenied && RecordingSync.hasToken(context)) RecorderService.start(context)
    }

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
        if (!sync.hasToken && !status.isRecording) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.record_needs_token))
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.record_open_settings)) }
                    }
                }
            }
        }
        item { StatusCard(status) }
        item {
            Button(
                enabled = status.isRecording || sync.hasToken,
                onClick = {
                    when {
                        status.isRecording -> RecorderService.stop(context)
                        !RecordingSync.hasToken(context) -> onOpenSettings()
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
            UploadCard(sync, onRetry = { RecordingSync.enqueue(context, ExistingWorkPolicy.REPLACE) })
        }
    }
}

@Composable
private fun UploadCard(sync: SyncUiState, onRetry: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.uploads_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (sync.pending.trips == 0) {
                    stringResource(R.string.uploads_done)
                } else {
                    pluralStringResource(
                        R.plurals.uploads_pending,
                        sync.pending.trips,
                        sync.pending.trips,
                        Formatter.formatShortFileSize(context, sync.pending.bytes),
                    )
                },
            )
            sync.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.uploads_note), style = MaterialTheme.typography.bodySmall)
            if (sync.hasToken && sync.pending.trips > 0 && !sync.running) {
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.uploads_retry)) }
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
                val quality = status.roadQuality
                val score = status.roadScore
                Text(
                    if (quality == null || score == null) {
                        stringResource(R.string.record_quality_waiting)
                    } else {
                        stringResource(R.string.record_quality, stringResource(quality.labelRes), score)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.record_potholes, status.potholes))
                Text(stringResource(R.string.record_bumps, status.speedBumps))
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
