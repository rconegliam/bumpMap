package com.rconegliam.bumpmap.recorder

import android.content.Context
import com.rconegliam.bumpmap.detection.DetectionStore
import com.rconegliam.bumpmap.sync.RecordingSync
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Finished recordings waiting for the server, counted per trip. */
data class PendingUploads(val trips: Int = 0, val bytes: Long = 0)

/**
 * App-private staging for recordings. A recording streams to these files while the car moves and
 * stays here only until the server acknowledged it; the upload worker then deletes it.
 */
object RecordingStore {
    private const val DIR = "recordings"
    private val activeNames = mutableSetOf<String>()

    fun fileName(now: Date): String =
        "bumpmap_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now) + ".csv"

    /** First unused base name; suffixes keep the legacy name format when a second is reused. */
    internal fun freeBaseName(baseName: String, isTaken: (String) -> Boolean): String =
        generateSequence(0) { it + 1 }
            .map { if (it == 0) baseName else "$baseName-$it" }
            .first { !isTaken(it) }

    @Synchronized
    fun newFile(context: Context, now: Date = Date()): File {
        val directory = dir(context)
        val baseName = fileName(now).removeSuffix(".csv")
        val candidate = freeBaseName(baseName) { name ->
            "$name.csv" in activeNames || DetectionStore.fileFor(context, "$name.csv").exists() ||
                RecordingSync.wasArchived(context, name) || !File(directory, "$name.csv").createNewFile()
        }
        activeNames.add("$candidate.csv")
        return File(directory, "$candidate.csv")
    }

    @Synchronized
    fun finish(file: File) {
        activeNames.remove(file.name)
    }

    /** Finished files to archive, including legacy files saved by earlier app versions. */
    @Synchronized
    fun archiveFiles(context: Context): List<File> =
        (list(context) + DetectionStore.list(context))
            .filter { "${it.nameWithoutExtension}.csv" !in activeNames }
            .sortedBy { it.name }

    fun pending(context: Context): PendingUploads {
        val files = archiveFiles(context)
        return PendingUploads(files.map { it.nameWithoutExtension }.distinct().size, files.sumOf { it.length() })
    }

    private fun list(context: Context): List<File> =
        dir(context).listFiles { file -> file.extension == "csv" }?.toList().orEmpty()

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
}
