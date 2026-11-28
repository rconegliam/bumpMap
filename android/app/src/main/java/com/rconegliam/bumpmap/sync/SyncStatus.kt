package com.rconegliam.bumpmap.sync

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rconegliam.bumpmap.R
import com.rconegliam.bumpmap.recorder.PendingUploads
import com.rconegliam.bumpmap.recorder.RecorderState
import com.rconegliam.bumpmap.recorder.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SyncUiState(
    val hasToken: Boolean,
    val pending: PendingUploads,
    val running: Boolean,
    @StringRes val message: Int?,
)

@Composable
internal fun rememberSyncState(tokenVersion: Int = 0): SyncUiState {
    val context = LocalContext.current.applicationContext
    val work by remember(context) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(RecordingSync.WORK_NAME)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingVersion by RecordingSync.pendingVersion.collectAsStateWithLifecycle()
    val recorder by RecorderState.status.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf(PendingUploads()) }
    LaunchedEffect(pendingVersion, recorder.finishedCount, recorder.isRecording, work) {
        pending = withContext(Dispatchers.IO) { RecordingStore.pending(context) }
    }
    val hasToken = remember(tokenVersion, work) { RecordingSync.hasToken(context) }
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    return SyncUiState(hasToken, pending, running, syncMessage(context, hasToken, pending, work))
}

@StringRes
private fun syncMessage(context: Context, hasToken: Boolean, pending: PendingUploads, work: List<WorkInfo>): Int? {
    if (!hasToken) return if (pending.trips > 0) R.string.sync_off else null
    if (work.any { it.state == WorkInfo.State.RUNNING }) return R.string.sync_running
    if (pending.trips == 0) return null
    val waitingForConnection = if (RecordingSync.usesMobileData(context)) {
        R.string.sync_waiting_network
    } else {
        R.string.sync_waiting_wifi
    }
    val waiting = work.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    if (waiting.isNotEmpty()) {
        return if (waiting.any { it.runAttemptCount > 0 }) R.string.sync_retrying else waitingForConnection
    }
    val failure = work.firstNotNullOfOrNull { info ->
        info.outputData.getString(SYNC_OUTCOME)?.let { runCatching { ArchiveOutcome.valueOf(it) }.getOrNull() }
    }
    return when (failure) {
        ArchiveOutcome.UNAUTHORIZED -> R.string.sync_unauthorized
        ArchiveOutcome.ARCHIVE_FULL -> R.string.sync_archive_full
        ArchiveOutcome.REJECTED -> R.string.sync_rejected
        else -> waitingForConnection
    }
}
