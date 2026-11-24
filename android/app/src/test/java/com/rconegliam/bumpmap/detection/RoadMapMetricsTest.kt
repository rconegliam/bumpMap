package com.rconegliam.bumpmap.detection

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMapMetricsTest {
    @Test
    fun aCloserReturnPassDoesNotInheritAnEarlierPothole() {
        val start = GeoPoint(-23.53, -46.76)
        val end = start.copy(lon = -46.7596)
        val returnStart = start.copy(lat = -23.52994)
        val returnEnd = end.copy(lat = returnStart.lat)
        val lines = sequenceOf(
            segment(start, end, 30.0, 100, 200),
            event(returnStart.copy(lon = -46.7598), EventType.POTHOLE, 160),
            segment(returnEnd, returnStart, 45.0, 300, 400),
        )
        val features = mapFeatures(lines, UUID.randomUUID())
        assertEquals(listOf(1, 0), features.mapNotNull(RoadMapMetrics::from).map { it.potholes })
        assertTrue(features.none { it.hasProperty("t_ns") || it.hasProperty("start_ns") || it.hasProperty("end_ns") })
    }

    @Test
    fun countsObstaclesOnlyOnTheirNearestMeasuredStretch() {
        val first = GeoPoint(-23.53, -46.76)
        val end = first.copy(lon = -46.7597)
        val next = end.copy(lon = -46.7594)
        val recording = UUID.randomUUID()
        val lines = listOf(
            event(first.copy(lon = -46.7599), EventType.POTHOLE),
            segment(first, end, 30.0),
            event(first.copy(lon = -46.7598), EventType.POTHOLE),
            event(end.copy(lon = -46.7595), EventType.SPEED_BUMP),
            segment(end, next, 45.0),
            event(next.copy(lat = -23.52), EventType.POTHOLE),
        )
        val features = mapFeatures(lines.asSequence(), recording)
        val measurements = features.mapNotNull(RoadMapMetrics::from)

        assertEquals(listOf(2, 0), measurements.map { it.potholes })
        assertEquals(listOf(0, 1), measurements.map { it.speedBumps })
        assertEquals(listOf(30.0, 45.0), measurements.map { it.meanSpeedKmh })
        assertEquals(features.map { it.id() }, mapFeatures(lines.asSequence(), recording).map { it.id() })
    }

    @Test
    fun moreObstaclesAddPointsAndFasterSmoothRoadsRankBest() {
        val best = RoadMapMetrics(60.0, 100, 0, 0)
        val slow = best.copy(meanSpeedKmh = 20.0)
        val bump = best.copy(speedBumps = 1)
        val hole = best.copy(potholes = 1)
        val holes = best.copy(potholes = 3)

        assertTrue(best.penaltyPoints < slow.penaltyPoints)
        assertTrue(slow.penaltyPoints < bump.penaltyPoints)
        assertTrue(bump.penaltyPoints < hole.penaltyPoints)
        assertTrue(hole.penaltyPoints < holes.penaltyPoints)
        assertEquals(RoadQuality.GOOD, best.quality)
        assertEquals(RoadQuality.BAD, holes.quality)
    }

    private fun segment(start: GeoPoint, end: GeoPoint, speedKmh: Double, startNs: Long? = null, endNs: Long? = null) =
        GeoJson.feature(RoadSegment(
            listOf(start, end), 30.0, speedKmh / 3.6, 0.0, 0.0, 100, RoadQuality.GOOD, startNs, endNs,
        ))

    private fun event(position: GeoPoint, type: EventType, timeNs: Long = 1) =
        GeoJson.feature(RoadEvent(type, timeNs, position, 8.0, 5.0))
}
