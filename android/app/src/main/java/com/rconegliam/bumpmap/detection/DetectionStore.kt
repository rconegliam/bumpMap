package com.rconegliam.bumpmap.detection

import android.content.Context
import java.io.File

/**
 * Detected segments and events, one GeoJSON feature per line, one file per recording. Like the CSV,
 * the file is staged only until the server archived it and published its observations.
 */
object DetectionStore {
    private const val DIR = "detections"

    fun fileFor(context: Context, recordingName: String): File =
        File(dir(context), recordingName.substringBeforeLast('.') + ".ndjson")

    fun list(context: Context): List<File> =
        dir(context).listFiles { file -> file.extension == "ndjson" }
            ?.toList()
            .orEmpty()

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
}
