package com.rconegliam.bumpmap.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rconegliam.bumpmap.recorder.RecordingStore
import com.rconegliam.bumpmap.detection.archiveRecordingId
import com.rconegliam.bumpmap.detection.mapFeatures
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

private const val PART_BYTES = 512 * 1024
private const val OBSERVATIONS_PER_BATCH = 40
internal const val ARCHIVE_FULL = "recording-archive-full"
private const val HTTP_INSUFFICIENT_STORAGE = 507

internal class RecordingUploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val checkpoint = context.getSharedPreferences("recording-upload-progress", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        val token = RecordingSync.token(applicationContext) ?: return Result.success()
        val installationId = RecordingSync.installationId(applicationContext)
        var hasRejectedFile = false
        var archiveFull = false
        for (file in RecordingStore.archiveFiles(applicationContext)) {
            if (isStopped) return Result.retry()
            try {
                val archive = uploadFile(file, installationId, token)
                val result = if (archive == HttpURLConnection.HTTP_NO_CONTENT && file.extension == "ndjson") {
                    publishDetections(file, installationId, token)
                } else archive
                when (result) {
                    HttpURLConnection.HTTP_NO_CONTENT -> Unit
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> return Result.failure()
                    HTTP_INSUFFICIENT_STORAGE -> archiveFull = true
                    HttpURLConnection.HTTP_CLIENT_TIMEOUT, HttpURLConnection.HTTP_PRECON_FAILED,
                    429, in 500..599 -> return Result.retry()
                    else -> hasRejectedFile = true
                }
            } catch (_: IOException) {
                if (file.exists()) return Result.retry()
            }
        }
        return when {
            archiveFull -> Result.failure(workDataOf(ARCHIVE_FULL to true))
            hasRejectedFile -> Result.failure()
            else -> Result.success()
        }
    }

    private fun uploadFile(file: File, installationId: String, token: String): Int {
        val id = archiveRecordingId(installationId, file.nameWithoutExtension)
        val progressKey = "$id/${file.extension}"
        val nextPart = checkpoint.getInt(progressKey, 0)
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val parts = (((size - 1).coerceAtLeast(0) / PART_BYTES) + 1).toInt()
            if (nextPart >= parts) return HttpURLConnection.HTTP_NO_CONTENT
            input.seek(nextPart.toLong() * PART_BYTES)
            for (number in nextPart until parts) {
                if (isStopped || RecordingSync.token(applicationContext) != token) {
                    return HttpURLConnection.HTTP_CLIENT_TIMEOUT
                }
                val bytes = ByteArray(minOf(PART_BYTES.toLong(), size - input.filePointer).toInt())
                input.readFully(bytes)
                val path = "/v1/recordings/$id/${file.extension}/$number?parts=$parts&name=${file.name}"
                val status = putPart(path, bytes, token)
                if (status == HttpURLConnection.HTTP_PRECON_FAILED &&
                    !checkpoint.edit().remove(progressKey).commit()
                ) throw IOException("Reset upload checkpoint")
                if (status != HttpURLConnection.HTTP_NO_CONTENT) return status
                if (!checkpoint.edit().putInt(progressKey, number + 1).commit()) throw IOException("Upload checkpoint")
            }
        }
        return HttpURLConnection.HTTP_NO_CONTENT
    }

    private fun publishDetections(file: File, installationId: String, token: String): Int {
        val id = archiveRecordingId(installationId, file.nameWithoutExtension)
        val publishedKey = "$id/map-v1"
        if (checkpoint.getBoolean(publishedKey, false)) return HttpURLConnection.HTTP_NO_CONTENT
        val features = file.useLines { mapFeatures(it, id) }
        for (batch in features.chunked(OBSERVATIONS_PER_BATCH)) {
            if (isStopped || RecordingSync.token(applicationContext) != token) {
                return HttpURLConnection.HTTP_CLIENT_TIMEOUT
            }
            val status = PilotMapApi.publish(batch, token)
            if (status != HttpURLConnection.HTTP_OK) return status
        }
        if (!checkpoint.edit().putBoolean(publishedKey, true).commit()) throw IOException("Map upload checkpoint")
        return HttpURLConnection.HTTP_NO_CONTENT
    }

    private fun putPart(path: String, bytes: ByteArray, token: String): Int {
        val connection = URL(PILOT_API + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
