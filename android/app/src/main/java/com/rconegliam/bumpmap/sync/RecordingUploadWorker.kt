package com.rconegliam.bumpmap.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rconegliam.bumpmap.recorder.RecordingStore
import org.maplibre.geojson.Feature
import java.util.UUID

internal const val SYNC_OUTCOME = "recording-sync-outcome"

internal class RecordingUploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val token = RecordingSync.token(applicationContext) ?: return failure(ArchiveOutcome.UNAUTHORIZED)
        val archiver = RecordingArchiver(
            api = HttpArchiveApi(token),
            checkpoints = PreferenceCheckpoints(RecordingSync.checkpoints(applicationContext)),
            installationId = RecordingSync.installationId(applicationContext),
            isCancelled = { isStopped || RecordingSync.token(applicationContext) != token },
            onPublished = RecordingSync::notifyPublished,
        )
        val outcome = archiver.run(RecordingStore.archiveFiles(applicationContext))
        RecordingSync.notifyPendingChanged()
        return when (outcome) {
            ArchiveOutcome.SUCCESS -> Result.success()
            ArchiveOutcome.RETRY -> Result.retry()
            else -> failure(outcome)
        }
    }

    private fun failure(outcome: ArchiveOutcome) = Result.failure(workDataOf(SYNC_OUTCOME to outcome.name))
}

private class HttpArchiveApi(private val token: String) : ArchiveApi {
    override fun putPart(id: UUID, kind: String, number: Int, parts: Int, name: String, bytes: ByteArray): Int =
        PilotMapApi.putPart("/v1/recordings/$id/$kind/$number?parts=$parts&name=$name", bytes, token)

    override fun publish(features: List<Feature>): Int = PilotMapApi.publish(features, token)
}

private class PreferenceCheckpoints(private val preferences: SharedPreferences) : ArchiveCheckpoints {
    override fun getInt(key: String) = preferences.getInt(key, 0)
    override fun putInt(key: String, value: Int) = preferences.edit().putInt(key, value).commit()
    override fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).commit()
    override fun remove(keys: List<String>) = preferences.edit().apply { keys.forEach(::remove) }.commit()
}
