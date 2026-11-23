package com.rconegliam.bumpmap.recorder

import com.rconegliam.bumpmap.detection.RoadQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RecorderStatus(
    val isRecording: Boolean = false,
    val fileName: String? = null,
    val startedAtMs: Long = 0,
    val sensorSamples: Long = 0,
    val locationFixes: Long = 0,
    val speedKmh: Float? = null,
    val accuracyM: Float? = null,
    val shakeRms: Double = 0.0,
    val marks: Int = 0,
    val potholes: Int = 0,
    val speedBumps: Int = 0,
    /** Score (0-100) and quality of the last rated stretch of road. */
    val roadScore: Int? = null,
    val roadQuality: RoadQuality? = null,
    /** Incremented every time a recording file is closed. */
    val finishedCount: Int = 0,
)

object RecorderState {
    private val mutableStatus = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = mutableStatus.asStateFlow()

    internal fun update(transform: (RecorderStatus) -> RecorderStatus) = mutableStatus.update(transform)
}
