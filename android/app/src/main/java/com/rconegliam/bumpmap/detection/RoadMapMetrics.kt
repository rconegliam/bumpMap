package com.rconegliam.bumpmap.detection

import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal const val POTHOLE_COUNT = "potholes"
internal const val SPEED_BUMP_COUNT = "speed_bumps"
internal const val MEAN_SPEED = "speed_kmh"
internal const val MAP_QUALITY = "map_quality"
internal const val PENALTY_POINTS = "penalty_points"

internal data class RoadMapMetrics(
    val meanSpeedKmh: Double,
    val pavementScore: Int?,
    val potholes: Int,
    val speedBumps: Int,
    private val serverPenaltyPoints: Long? = null,
    private val serverQuality: RoadQuality? = null,
) {
    val penaltyPoints: Long
        get() = serverPenaltyPoints ?: computePenaltyPoints(
            pavementScore = pavementScore ?: 0,
            meanSpeedKmh = meanSpeedKmh,
            potholes = potholes,
            speedBumps = speedBumps,
        )

    val quality: RoadQuality
        get() = serverQuality ?: qualityForPenalty(penaltyPoints)

    companion object {
        fun from(feature: Feature): RoadMapMetrics? {
            if (feature.geometry() !is LineString) return null
            val speed = feature.getNumberProperty(MEAN_SPEED)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val potholes = feature.getNumberProperty(POTHOLE_COUNT)?.toInt()
                ?.takeIf { it >= 0 } ?: return null
            val speedBumps = feature.getNumberProperty(SPEED_BUMP_COUNT)?.toInt()
                ?.takeIf { it >= 0 } ?: return null
            val serverQuality = mapQualityProperty(feature)
            val serverPenalty = penaltyPointsProperty(feature)
            val pavementScore = mapPavementScore(feature)
            if (serverQuality != null && serverPenalty != null) {
                return RoadMapMetrics(
                    meanSpeedKmh = speed,
                    pavementScore = pavementScore,
                    potholes = potholes,
                    speedBumps = speedBumps,
                    serverPenaltyPoints = serverPenalty,
                    serverQuality = serverQuality,
                )
            }
            return RoadMapMetrics(
                meanSpeedKmh = speed,
                pavementScore = pavementScore ?: return null,
                potholes = potholes,
                speedBumps = speedBumps,
            )
        }
    }
}

private fun computePenaltyPoints(pavementScore: Int, meanSpeedKmh: Double, potholes: Int, speedBumps: Int): Long =
    ((100 - pavementScore.coerceIn(0, 100)) * 0.25 +
        (1.0 - meanSpeedKmh.coerceIn(0.0, 60.0) / 60.0) * 10).roundToInt() +
        potholes * 20L + speedBumps * 12L

private fun qualityForPenalty(penaltyPoints: Long): RoadQuality = when {
    penaltyPoints < 8 -> RoadQuality.GOOD
    penaltyPoints < 20 -> RoadQuality.FAIR
    penaltyPoints < 40 -> RoadQuality.POOR
    else -> RoadQuality.BAD
}

private fun mapQualityProperty(feature: Feature): RoadQuality? {
    val code = feature.properties()?.get(MAP_QUALITY)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    return RoadQuality.entries.firstOrNull { it.code == code }
}

private fun penaltyPointsProperty(feature: Feature): Long? {
    val raw = feature.properties()?.get(PENALTY_POINTS)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return null
    if (!raw.isFinite() || raw < 0.0 || raw > Long.MAX_VALUE.toDouble() || raw % 1.0 != 0.0) return null
    return raw.toLong()
}

private fun mapPavementScore(feature: Feature): Int? {
    val roughness = feature.properties()?.get("roughness")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        ?.takeIf { it.isFinite() && it >= 0 }
        ?: return feature.getNumberProperty("score")?.toInt()
    return pavementScore(roughness)
}

private const val NEARBY_SEGMENTS = 8
private const val MAX_EVENT_DISTANCE_METERS = 18.0
private const val METERS_PER_DEGREE = 111_195.0

internal fun archiveRecordingId(installationId: String, baseName: String): UUID =
    UUID.nameUUIDFromBytes("$installationId/$baseName".encodeToByteArray())

/** Rate each measured stretch using nearby detections from the same pass. */
internal fun mapFeatures(lines: Sequence<String>, recordingId: UUID): List<Feature> {
    val features = lines.mapIndexedNotNull { index, line ->
        val feature = runCatching { Feature.fromJson(line) }.getOrNull() ?: return@mapIndexedNotNull null
        val geometry = feature.geometry()
        val properties = feature.properties()
        if (geometry == null || properties == null) return@mapIndexedNotNull null
        if (geometry !is LineString && geometry !is Point) return@mapIndexedNotNull null
        val id = UUID.nameUUIDFromBytes("$recordingId/map/$index".encodeToByteArray())
        val observation = Feature.fromGeometry(geometry, properties, id.toString())
        observation.addStringProperty("recording_id", recordingId.toString())
        IndexedValue(index, observation)
    }.toList()
    val segments = features.mapNotNull { (index, feature) ->
        val line = feature.geometry() as? LineString ?: return@mapNotNull null
        Segment(index, feature, line.coordinates())
    }

    for ((index, feature) in features) {
        val point = feature.geometry() as? Point ?: continue
        val type = feature.getStringProperty("type")
        if (type != EventType.POTHOLE.code && type != EventType.SPEED_BUMP.code) continue
        val insertion = segments.binarySearchBy(index) { it.index }.let { -it - 1 }
        // Sequence proximity keeps a later pass along the same street from receiving the event.
        val nearby = segments.subList(
            (insertion - NEARBY_SEGMENTS).coerceAtLeast(0),
            (insertion + NEARBY_SEGMENTS).coerceAtMost(segments.size),
        )
        var best: Segment? = null
        var bestDistance = MAX_EVENT_DISTANCE_METERS
        val eventTimeNs = feature.getNumberProperty("t_ns")?.toLong()
        for (candidate in nearby) {
            if (!candidate.covers(eventTimeNs)) continue
            val distance = candidate.distanceTo(point)
            if (distance < bestDistance ||
                (distance == bestDistance && best != null && abs(candidate.index - index) < abs(best.index - index))
            ) {
                best = candidate
                bestDistance = distance
            }
        }
        if (type == EventType.POTHOLE.code) best?.let { it.potholes++ }
        else best?.let { it.speedBumps++ }
    }

    for (segment in segments) {
        segment.feature.addNumberProperty(POTHOLE_COUNT, segment.potholes)
        segment.feature.addNumberProperty(SPEED_BUMP_COUNT, segment.speedBumps)
    }
    return features.map { (_, feature) ->
        // Elapsed time associates detections locally and is unnecessary in the shared map.
        feature.removeProperty("t_ns")
        feature.removeProperty("start_ns")
        feature.removeProperty("end_ns")
        feature
    }
}

private class Segment(val index: Int, val feature: Feature, private val points: List<Point>) {
    var potholes = 0
    var speedBumps = 0

    private val startNs = feature.getNumberProperty("start_ns")?.toLong()
    private val endNs = feature.getNumberProperty("end_ns")?.toLong()

    fun covers(eventTimeNs: Long?): Boolean =
        eventTimeNs == null || startNs == null || endNs == null || eventTimeNs in startNs..endNs

    fun distanceTo(point: Point): Double {
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(point.latitude()))
        fun x(position: Point): Double =
            ((position.longitude() - point.longitude() + 540) % 360 - 180) * longitudeScale
        fun y(position: Point): Double =
            (position.latitude() - point.latitude()) * METERS_PER_DEGREE

        return points.zipWithNext { a, b -> Geo.distanceToSegment(x(a), y(a), x(b), y(b)) }
            .minOrNull() ?: Double.POSITIVE_INFINITY
    }
}
