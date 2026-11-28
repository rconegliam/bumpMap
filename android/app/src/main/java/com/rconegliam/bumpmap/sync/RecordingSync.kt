package com.rconegliam.bumpmap.sync

import android.content.Context
import android.content.SharedPreferences
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
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val ENROLLMENT_TOKEN_BYTES = 32
private const val ENROLLMENT_TOKEN_HEX_LENGTH = ENROLLMENT_TOKEN_BYTES * 2

/**
 * Recordings are staged on the phone until PostgreSQL acknowledged them, and the map reads
 * observations from the server. Each installation automatically enrolls a random bearer token.
 */
internal object RecordingSync {
    const val WORK_NAME = "recording-sync"
    internal const val LEGACY_TOKEN = "token"
    internal const val ENROLLMENT_TOKEN = "enrollment-token"
    internal const val INSTALLATION_ID = "installation-id"
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

    /** Removes the deprecated user-entered write token without altering installation ID or checkpoints. */
    fun clearLegacyToken(context: Context) {
        clearLegacyToken(settings(context))
    }

    internal fun clearLegacyToken(preferences: SharedPreferences) {
        if (preferences.contains(LEGACY_TOKEN)) {
            preferences.edit().remove(LEGACY_TOKEN).commit()
        }
    }

    fun usesMobileData(context: Context): Boolean = settings(context).getBoolean(MOBILE_DATA, false)

    fun setUseMobileData(context: Context, allowed: Boolean) {
        settings(context).edit().putBoolean(MOBILE_DATA, allowed).commit()
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
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
    fun installationId(context: Context): String = installationId(settings(context))

    internal fun installationId(preferences: SharedPreferences): String =
        preferences.getString(INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(INSTALLATION_ID, it).commit()) { "Cannot save installation ID" }
        }

    /**
     * Returns this installation's durable 64-char lowercase hex bearer token, generating and
     * committing it before the first upload request. Never overwritten on conflict.
     */
    @Synchronized
    fun enrollmentToken(context: Context): String = enrollmentToken(settings(context))

    internal fun enrollmentToken(
        preferences: SharedPreferences,
        random: SecureRandom = SecureRandom(),
    ): String {
        preferences.getString(ENROLLMENT_TOKEN, null)?.takeIf(::isValidEnrollmentToken)?.let { return it }
        return generateEnrollmentToken(random).also {
            check(preferences.edit().putString(ENROLLMENT_TOKEN, it).commit()) { "Cannot save enrollment token" }
        }
    }
}

internal fun generateEnrollmentToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(ENROLLMENT_TOKEN_BYTES)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

internal fun isValidEnrollmentToken(token: String): Boolean =
    token.length == ENROLLMENT_TOKEN_HEX_LENGTH && token.all { it in '0'..'9' || it in 'a'..'f' }
