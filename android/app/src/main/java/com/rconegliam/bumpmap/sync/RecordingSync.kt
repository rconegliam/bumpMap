package com.rconegliam.bumpmap.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

internal object RecordingSync {
    const val WORK_NAME = "recording-sync"
    private const val TOKEN = "token"
    private const val INSTALLATION_ID = "installation-id"

    private fun settings(context: Context) = context.getSharedPreferences("recording-sync", Context.MODE_PRIVATE)

    fun token(context: Context): String? = settings(context).getString(TOKEN, null)

    fun enable(context: Context, token: String) {
        settings(context).edit().putString(TOKEN, token).apply()
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun disable(context: Context) {
        settings(context).edit().remove(TOKEN).apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (token(context) == null) return
        val request = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.UNMETERED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    @Synchronized
    fun installationId(context: Context): String {
        val settings = settings(context)
        return settings.getString(INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            check(settings.edit().putString(INSTALLATION_ID, it).commit()) { "Cannot save installation ID" }
        }
    }
}
