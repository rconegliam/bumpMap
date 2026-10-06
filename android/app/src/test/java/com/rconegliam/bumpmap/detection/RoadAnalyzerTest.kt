package com.rconegliam.bumpmap.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RoadAnalyzerTest {
    private val segments = mutableListOf<RoadSegment>()
    private val events = mutableListOf<RoadEvent>()
    private val analyzer = RoadAnalyzer(onSegment = { segments += it }, onEvent = { events += it })

    private val start = GeoPoint(-23.5110, -46.8760)
    private var tNs = 1_000_000_000L
    private var travelledM = 0.0

    /** Drives north at [speedKmh] for [seconds], feeding 100 Hz vertical acceleration from [signal]. */
    private fun drive(speedKmh: Double, seconds: Double, accuracyM: Double = 5.0, signal: (Double) -> Double) {
        val speed = speedKmh / 3.6
        val steps = (seconds * 100).toInt()
        val startNs = tNs
        repeat(steps) { i ->
            tNs += 10_000_000L
            travelledM += speed * 0.01
            analyzer.onVertical(tNs, signal((tNs - startNs) / 1e9))
            if ((i + 1) % 100 == 0) {
                val p = Geo.destination(start, 0.0, travelledM)
                analyzer.onLocation(tNs, p.lat, p.lon, speed, 0.0, accuracyM)
            }
        }
    }

    private fun noise(amplitude: Double) = { t: Double -> amplitude * sin(2 * PI * 13 * t) }

    @Test
    fun smoothRoadIsGoodAndHasNoEvents() {
        drive(40.0, 20.0, signal = noise(0.3))
        assertTrue(segments.size >= 5)
        assertTrue(segments.all { it.quality == RoadQuality.GOOD })
        assertTrue(segments.all { it.score > 80 })
        assertTrue(events.isEmpty())
    }

    @Test
    fun roughRoadIsBad() {
        drive(40.0, 20.0, signal = noise(3.0))
        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it.quality == RoadQuality.BAD })
        assertTrue(segments.all { it.score < 40 })
    }

    @Test
    fun segmentsAreAboutTwentyFiveMetresLong() {
        drive(60.0, 30.0, signal = noise(0.3))
        assertTrue(segments.all { it.lengthM in 25.0..45.0 })
        val total = segments.sumOf { it.lengthM }
        assertTrue("total $total", total > 400)
    }

    @Test
    fun noSegmentsWhenTooSlowOrBadGps() {
        drive(8.0, 30.0, signal = noise(0.3))
        drive(40.0, 20.0, accuracyM = 60.0, signal = noise(0.3))
        assertTrue(segments.isEmpty())
    }

    @Test
    fun sameShakeAtHigherSpeedRatesSmoother() {
        val slow = analyzer.roughness(rms = 1.0, speedMps = 20 / 3.6)
        val fast = analyzer.roughness(rms = 1.0, speedMps = 80 / 3.6)
        assertTrue(fast < slow)
    }

    @Test
    fun sharpSpikeIsPothole() {
        drive(40.0, 3.0, signal = noise(0.3))
        drive(40.0, 3.0) { t -> if (t in 1.0..1.04) 9.0 else if (t in 1.04..1.08) -7.0 else 0.0 }
        drive(40.0, 3.0, signal = noise(0.3))
        assertEquals(listOf(EventType.POTHOLE), events.map { it.type })
    }

    @Test
    fun slowHeaveIsSpeedBump() {
        drive(20.0, 3.0, signal = noise(0.3))
        drive(20.0, 3.0) { t -> if (t in 1.0..1.5) 3.0 * sin(2 * PI * (t - 1.0)) else 0.0 }
        drive(20.0, 3.0, signal = noise(0.3))
        assertEquals(listOf(EventType.SPEED_BUMP), events.map { it.type })
        assertTrue(events.single().peak > 1.2)
    }

    @Test
    fun frontAndRearWheelsCountAsOneBump() {
        val heave = { t: Double -> if (t in 1.0..1.5) 3.0 * sin(2 * PI * (t - 1.0)) else 0.0 }
        drive(15.0, 3.0, signal = noise(0.3))
        drive(15.0, 3.0) { t -> heave(t) + heave(t - 0.6) }
        assertEquals(1, events.size)
    }

    @Test
    fun noEventsWhenStopped() {
        drive(0.0, 3.0) { t -> if (t in 1.0..1.04) 9.0 else 0.0 }
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventPositionIsAlongTheRoute() {
        drive(40.0, 3.0, signal = noise(0.3))
        drive(40.0, 3.0) { t -> if (t in 1.5..1.54) 9.0 else 0.0 }
        val event = events.single()
        val expectedM = 40 / 3.6 * 4.5
        val actualM = Geo.distanceM(start, event.position)
        assertEquals(expectedM, actualM, 15.0)
    }

    @Test
    fun scoreAndQualityThresholds() {
        assertEquals(100, analyzer.score(0.1))
        assertEquals(0, analyzer.score(5.0))
        assertEquals(RoadQuality.GOOD, analyzer.quality(0.5))
        assertEquals(RoadQuality.FAIR, analyzer.quality(0.8))
        assertEquals(RoadQuality.POOR, analyzer.quality(1.2))
        assertEquals(RoadQuality.BAD, analyzer.quality(2.0))
    }
}
