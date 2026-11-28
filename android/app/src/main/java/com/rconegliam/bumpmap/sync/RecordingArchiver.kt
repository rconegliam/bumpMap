package com.rconegliam.bumpmap.sync

import com.rconegliam.bumpmap.detection.archiveRecordingId
import com.rconegliam.bumpmap.detection.mapFeatures
import org.maplibre.geojson.Feature
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.UUID

internal const val ARCHIVE_PART_BYTES = 512 * 1024
private const val OBSERVATIONS_PER_BATCH = 40
private const val HTTP_INSUFFICIENT_STORAGE = 507
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_CANCELLED = HttpURLConnection.HTTP_CLIENT_TIMEOUT

internal interface ArchiveApi {
    fun registerInstallation(installationId: String): Int
    fun putPart(id: UUID, kind: String, number: Int, parts: Int, name: String, bytes: ByteArray): Int
    fun publish(features: List<Feature>): Int
}

/** Durable upload progress. Writes return false when they could not be persisted. */
internal interface ArchiveCheckpoints {
    fun getInt(key: String): Int
    fun putInt(key: String, value: Int): Boolean
    fun putBoolean(key: String, value: Boolean): Boolean
    fun remove(keys: List<String>): Boolean
}

internal enum class ArchiveOutcome { SUCCESS, RETRY, UNAUTHORIZED, ARCHIVE_FULL, REJECTED }

internal fun archivedKey(id: UUID) = "$id/archived"

/**
 * Sends finished recordings to the server and deletes the local copy of each recording only after
 * the server acknowledged every expected file byte-for-byte and its map observations.
 */
internal class RecordingArchiver(
    private val api: ArchiveApi,
    private val checkpoints: ArchiveCheckpoints,
    private val installationId: String,
    private val isCancelled: () -> Boolean = { false },
    private val onPublished: () -> Unit = {},
) {
    fun run(files: List<File>): ArchiveOutcome {
        val grouped = files
            .filter { it.exists() && (it.extension == "csv" || it.extension == "ndjson") }
            .groupBy { it.nameWithoutExtension }
            .toSortedMap()
        if (grouped.isEmpty()) return ArchiveOutcome.SUCCESS
        if (isCancelled()) return ArchiveOutcome.RETRY
        val registrationStatus = try {
            api.registerInstallation(installationId)
        } catch (_: IOException) {
            return ArchiveOutcome.RETRY
        }
        when (registrationStatus) {
            HttpURLConnection.HTTP_NO_CONTENT -> Unit
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN, HttpURLConnection.HTTP_CONFLICT ->
                return ArchiveOutcome.UNAUTHORIZED
            HTTP_CANCELLED, HTTP_TOO_MANY_REQUESTS, in 500..599 -> return ArchiveOutcome.RETRY
            else -> return ArchiveOutcome.REJECTED
        }
        var archiveFull = false
        var rejected = false
        for ((baseName, recordingFiles) in grouped) {
            if (isCancelled()) return ArchiveOutcome.RETRY
            val status = try {
                archive(baseName, recordingFiles.sortedBy { it.extension })
            } catch (_: IOException) {
                if (recordingFiles.any { it.exists() }) return ArchiveOutcome.RETRY else continue
            }
            when (status) {
                HttpURLConnection.HTTP_NO_CONTENT -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> return ArchiveOutcome.UNAUTHORIZED
                HTTP_INSUFFICIENT_STORAGE -> archiveFull = true
                HTTP_CANCELLED, HttpURLConnection.HTTP_PRECON_FAILED, HTTP_TOO_MANY_REQUESTS,
                in 500..599 -> return ArchiveOutcome.RETRY
                else -> rejected = true
            }
        }
        return when {
            archiveFull -> ArchiveOutcome.ARCHIVE_FULL
            rejected -> ArchiveOutcome.REJECTED
            else -> ArchiveOutcome.SUCCESS
        }
    }

    private fun archive(baseName: String, files: List<File>): Int {
        val id = archiveRecordingId(installationId, baseName)
        val existing = files.filter { it.exists() && (it.extension == "csv" || it.extension == "ndjson") }
        if (existing.isEmpty()) return HttpURLConnection.HTTP_NO_CONTENT
        for (file in existing) {
            val status = uploadFile(id, file)
            if (status != HttpURLConnection.HTTP_NO_CONTENT) return status
        }
        existing.firstOrNull { it.extension == "ndjson" }?.let { detections ->
            val status = publishDetections(id, detections)
            if (status != HttpURLConnection.HTTP_NO_CONTENT) return status
        }
        if (!checkpoints.putBoolean(archivedKey(id), true)) throw IOException("Archive checkpoint")
        for (file in existing) {
            if (!file.delete() && file.exists()) throw IOException("Delete archived ${file.name}")
        }
        checkpoints.remove(listOf("$id/csv", "$id/ndjson", "$id/map-v1"))
        return HttpURLConnection.HTTP_NO_CONTENT
    }

    /** Uploads missing parts, or re-sends the last part to confirm a file uploaded by an earlier run. */
    private fun uploadFile(id: UUID, file: File): Int {
        val progressKey = "$id/${file.extension}"
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val parts = (((size - 1).coerceAtLeast(0) / ARCHIVE_PART_BYTES) + 1).toInt()
            // The server stores a part only when every earlier part exists, and answers 204 for an
            // identical retry, so acknowledging the last part confirms the whole file.
            val first = checkpoints.getInt(progressKey).coerceAtMost(parts - 1)
            input.seek(first.toLong() * ARCHIVE_PART_BYTES)
            for (number in first until parts) {
                if (isCancelled()) return HTTP_CANCELLED
                val bytes = ByteArray(minOf(ARCHIVE_PART_BYTES.toLong(), size - input.filePointer).toInt())
                input.readFully(bytes)
                val status = api.putPart(id, file.extension, number, parts, file.name, bytes)
                if (status == HttpURLConnection.HTTP_PRECON_FAILED && !checkpoints.remove(listOf(progressKey))) {
                    throw IOException("Reset upload checkpoint")
                }
                if (status != HttpURLConnection.HTTP_NO_CONTENT) return status
                if (!checkpoints.putInt(progressKey, number + 1)) throw IOException("Upload checkpoint")
            }
        }
        return HttpURLConnection.HTTP_NO_CONTENT
    }

    /**
     * Publishes every observation, even when an earlier run already did, so the map is confirmed on
     * the server before the local file goes away. Repeating identical observations is idempotent.
     */
    private fun publishDetections(id: UUID, file: File): Int {
        val features = file.useLines { mapFeatures(it, id) }
        for (batch in features.chunked(OBSERVATIONS_PER_BATCH)) {
            if (isCancelled()) return HTTP_CANCELLED
            val status = when (val batchStatus = api.publish(batch)) {
                HttpURLConnection.HTTP_CONFLICT -> publishOneByOne(batch)
                else -> batchStatus
            }
            if (status != HttpURLConnection.HTTP_OK) return status
        }
        if (features.isNotEmpty()) onPublished()
        return HttpURLConnection.HTTP_NO_CONTENT
    }

    /**
     * A conflict means the server already holds an observation with that ID and different content,
     * for example one published by an older app version. Either way the observation is on the map.
     */
    private fun publishOneByOne(batch: List<Feature>): Int {
        for (feature in batch) {
            if (isCancelled()) return HTTP_CANCELLED
            val status = api.publish(listOf(feature))
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_CONFLICT) return status
        }
        return HttpURLConnection.HTTP_OK
    }
}
