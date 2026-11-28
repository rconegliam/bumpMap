package com.rconegliam.bumpmap.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rconegliam.bumpmap.detection.archiveRecordingId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Server sync is required: recordings are only staged on the phone until PostgreSQL acknowledged
 * them, and the map reads observations from the server.
 */
internal object RecordingSync {
    const val WORK_NAME = "recording-sync"
    private const val TOKEN = "token"
    private const val INSTALLATION_ID = "installation-id"
    private const val MOBILE_DATA = "mobile-data"

    private val mutablePublished = MutableStateFlow(0)
    private val mutablePending = MutableStateFlow(0)

    /** Changes whenever this phone published new observations to the server map. */
    val publishedVersion: StateFlow<Int> = mutablePublished.asStateFlow()

    /** Changes whenever staged recordings may have been archived and removed. */
    val pendingVersion: StateFlow<Int> = mutablePending.asStateFlow()

    private fun settings(context: Context) = context.getSharedPreferences("recording-sync", Context.MODE_PRIVATE)

    fun checkpoints(context: Context) =
        context.getSharedPreferences("recording-upload-progress", Context.MODE_PRIVATE)

    fun token(context: Context): String? = settings(context).getString(TOKEN, null)

    fun hasToken(context: Context): Boolean = token(context) != null

    fun saveToken(context: Context, token: String): Boolean {
        if (!settings(context).edit().putString(TOKEN, token).commit()) return false
        enqueue(context, ExistingWorkPolicy.REPLACE)
        return true
    }

    /** Pending recordings stay staged until a token is saved again. */
    fun removeToken(context: Context) {
        settings(context).edit().remove(TOKEN).commit()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun usesMobileData(context: Context): Boolean = settings(context).getBoolean(MOBILE_DATA, false)

    fun setUseMobileData(context: Context, allowed: Boolean) {
        settings(context).edit().putBoolean(MOBILE_DATA, allowed).commit()
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (!hasToken(context)) return
        val network = if (usesMobileData(context)) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
            .setConstraints(Constraints(requiredNetworkType = network))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    fun notifyPublished() = mutablePublished.update { it + 1 }

    fun notifyPendingChanged() = mutablePending.update { it + 1 }

    /** True when a recording with this base name was archived, so the name must not be reused. */
    fun wasArchived(context: Context, baseName: String): Boolean =
        checkpoints(context).getBoolean(archivedKey(recordingId(context, baseName)), false)

    private fun recordingId(context: Context, baseName: String): UUID =
        archiveRecordingId(installationId(context), baseName)

    @Synchronized
    fun installationId(context: Context): String {
        val settings = settings(context)
        return settings.getString(INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            check(settings.edit().putString(INSTALLATION_ID, it).commit()) { "Cannot save installation ID" }
        }
    }
}
