package com.rconegliam.bumpmap.api

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal const val MAX_BATCH_SIZE = 100
internal const val PAGE_SIZE = 500

// Allow for rounding when subtracting coordinates at the largest valid magnitude.
private val MAX_BOX_SPAN = 1.0 + Math.ulp(180.0)

@Serializable
internal data class ObservationBatch(
    val type: String,
    val features: List<Observation>,
)

@Serializable
internal data class ObservationPage(
    val type: String,
    val features: List<Observation>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class Observation(
    val type: String,
    val id: String,
    val geometry: Geometry,
    val properties: ObservationProperties,
) {
    fun valid(): Boolean {
        if (type != "Feature" || parseId(id) == null || !properties.speedKmh.isFinite() ||
            properties.speedKmh < 0
        ) return false
        if (properties.recordingId != null && parseId(properties.recordingId) == null) return false

        val coordinates = geometry.coordinates
        return when (properties.kind) {
            "segment" -> geometry.type == "LineString" &&
                coordinates.isLine() &&
                properties.quality in qualities && properties.score != null && properties.score in 0..100 &&
                properties.roughness.isMagnitude() && properties.eventType == null && properties.peak == null &&
                properties.validObstacleCounts()
            "event" -> geometry.type == "Point" && coordinates.position() != null &&
                properties.eventType in eventTypes && properties.peak.isMagnitude() &&
                properties.quality == null && properties.score == null && properties.roughness == null &&
                properties.potholes == null && properties.speedBumps == null
            else -> false
        }
    }
}

@Serializable
internal data class Geometry(val type: String, val coordinates: JsonArray)

@Serializable
internal data class ObservationProperties(
    val kind: String,
    @SerialName("speed_kmh") val speedKmh: Double,
    val quality: String? = null,
    val score: Int? = null,
    val roughness: Double? = null,
    @SerialName("type") val eventType: String? = null,
    val peak: Double? = null,
    @SerialName("recording_id") val recordingId: String? = null,
    val potholes: Int? = null,
    @SerialName("speed_bumps") val speedBumps: Int? = null,
) {
    fun validObstacleCounts() = if (recordingId == null) {
        potholes == null && speedBumps == null
    } else {
        potholes != null && potholes >= 0 && speedBumps != null && speedBumps >= 0
    }
}

internal data class BoundingBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    companion object {
        fun parse(value: String?): BoundingBox? {
            val parts = value?.split(',', limit = 5) ?: return null
            if (parts.size != 4) return null
            val (west, south, east, north) = parts.map { it.toDoubleOrNull() ?: return null }
            val box = BoundingBox(west, south, east, north)
            if (west !in -180.0..180.0 || east !in -180.0..180.0 ||
                south !in -90.0..90.0 || north !in -90.0..90.0 ||
                east <= west || north <= south ||
                east - west > MAX_BOX_SPAN || north - south > MAX_BOX_SPAN
            ) return null
            return box
        }
    }
}

internal fun parseId(value: String): UUID? = try {
    UUID.fromString(value).takeIf { it.toString() == value }
} catch (_: IllegalArgumentException) {
    null
}

private val qualities = setOf("good", "fair", "poor", "bad")
private val eventTypes = setOf("pothole", "speed_bump")

private fun Double?.isMagnitude() = this != null && isFinite() && this >= 0

private fun JsonArray.isLine(): Boolean {
    if (size !in 2..128) return false
    val positions = map { (it as? JsonArray)?.position() ?: return false }
    return positions.any { it != positions.first() }
}

private fun JsonArray.position(): Pair<Double, Double>? {
    if (size != 2) return null
    val longitude = (get(0) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
    val latitude = (get(1) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
    if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null
    return (longitude + 0.0) to (latitude + 0.0)
}
