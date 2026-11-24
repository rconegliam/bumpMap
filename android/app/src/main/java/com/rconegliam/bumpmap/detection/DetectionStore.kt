package com.rconegliam.bumpmap.detection

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

/**
 * Detected segments and events, one GeoJSON feature per line, one file per recording. Kept even when
 * the (much larger) CSV recording is deleted, so the map keeps the results.
 */
object DetectionStore {
    private const val DIR = "detections"
    private val mutableVersion = MutableStateFlow(0)

    /** Changes whenever new detections are written. */
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    fun fileFor(context: Context, recordingName: String): File =
        File(dir(context), recordingName.substringBeforeLast('.') + ".ndjson")

    fun notifyChanged() = mutableVersion.update { it + 1 }

    fun list(context: Context): List<File> =
        dir(context).listFiles { file -> file.extension == "ndjson" }
            ?.toList()
            .orEmpty()

    internal fun loadMapFeatures(context: Context, installationId: String) =
        list(context)
            .sortedBy { it.name }
            .flatMap { file ->
                try {
                    file.useLines { mapFeatures(it, archiveRecordingId(installationId, file.nameWithoutExtension)) }
                } catch (_: IOException) {
                    emptyList()
                }
            }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
}
