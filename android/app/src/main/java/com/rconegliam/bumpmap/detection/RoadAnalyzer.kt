package com.rconegliam.bumpmap.detection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns vertical acceleration (gravity removed, ~100 Hz) and GPS fixes into rated road segments
 * and detected potholes / speed bumps. Not thread-safe: call from one thread.
 */
class RoadAnalyzer(
    private val config: DetectionConfig = DetectionConfig(),
    private val onSegment: (RoadSegment) -> Unit,
    private val onEvent: (RoadEvent) -> Unit,
) {
    private var lastFix: GeoPoint? = null
    private var lastFixNs = 0L
    private var speedMps = Double.NaN
    private var bearingDeg = Double.NaN
    private var stoppedAt: GeoPoint? = null

    private val segmentPoints = ArrayList<GeoPoint>()
    private var segmentLengthM = 0.0
    private var segmentSumSquares = 0.0
    private var segmentSamples = 0
    private var segmentSpeedSum = 0.0
    private var segmentSpeedCount = 0
    private var intervalSumSquares = 0.0
    private var intervalSamples = 0

    private val lowPassRc = 1.0 / (2 * PI * config.lowPassHz)
    private var lowPass = 0.0
    private var lastSampleNs = Long.MIN_VALUE

    private var eventCandidate: EventCandidate? = null
    private var refractoryUntilNs = Long.MIN_VALUE

    fun onVertical(tNs: Long, verticalAcc: Double) {
        val dtNs = if (lastSampleNs == Long.MIN_VALUE) 0L else (tNs - lastSampleNs).coerceIn(0L, MAX_SAMPLE_GAP_NS)
        lastSampleNs = tNs
        val dtS = dtNs / 1e9
        val alpha = if (dtNs == 0L) 1.0 else dtS / (lowPassRc + dtS)
        lowPass += alpha * (verticalAcc - lowPass)
        val highPass = verticalAcc - lowPass

        intervalSumSquares += verticalAcc * verticalAcc
        intervalSamples++
        detectEvent(tNs, dtNs, highPass)
    }

    /** Pass NaN for values the fix does not have. */
    fun onLocation(tNs: Long, lat: Double, lon: Double, speedMps: Double, bearingDeg: Double, accuracyM: Double) {
        val sumSquares = intervalSumSquares
        val samples = intervalSamples
        intervalSumSquares = 0.0
        intervalSamples = 0
        val point = GeoPoint(lat, lon)
        if (accuracyM.isNaN() || accuracyM > config.maxAccuracyM) {
            lastFix = null
            this.speedMps = Double.NaN
            stoppedAt = null
            eventCandidate = null
            resetSegment()
            return
        }
        val fixGapNs = tNs - lastFixNs
        val previous = lastFix?.takeIf { fixGapNs in 1..config.maxFixGapNs }
        if (previous == null || fixGapNs > config.maxExtrapolationNs) eventCandidate = null
        val speed = when {
            !speedMps.isNaN() -> speedMps
            previous != null -> Geo.distanceM(previous, point) / (fixGapNs / 1e9)
            else -> Double.NaN
        }
        this.speedMps = speed
        this.bearingDeg = bearingDeg
        lastFix = point
        lastFixNs = tNs

        if (previous == null) {
            resetSegment()
            stoppedAt = point.takeIf { speedMps == 0.0 }
            return
        }
        val stop = stoppedAt
        if (speedMps == 0.0) {
            if (stop != null && Geo.distanceM(stop, point) <= accuracyM) return
            // A changed position on the first zero-speed fix can be braking distance.
            if (stop != null || point != previous) resetSegment()
            stoppedAt = point
            return
        }
        stoppedAt = null
        val segmentStart = segmentPoints.lastOrNull() ?: stop ?: previous
        val distance = Geo.distanceM(segmentStart, point)
        if (distance == 0.0) return
        if (!speed.isFinite() || speed < config.minSegmentSpeedMps) {
            // Restart rather than drawing a line across unrated movement.
            resetSegment()
            return
        }
        if (segmentPoints.isEmpty()) segmentPoints.add(segmentStart)
        segmentPoints.add(point)
        segmentLengthM += distance
        segmentSumSquares += sumSquares
        segmentSamples += samples
        segmentSpeedSum += speed
        segmentSpeedCount++
        if (segmentLengthM >= config.segmentLengthM) {
            closeSegment()
            resetSegment()
        }
    }

    private fun closeSegment() {
        if (segmentSpeedCount == 0 || segmentSamples < config.minSegmentSamples) return
        val meanSpeed = segmentSpeedSum / segmentSpeedCount
        val rms = sqrt(segmentSumSquares / segmentSamples)
        val roughness = roughness(rms, meanSpeed)
        onSegment(
            RoadSegment(
                points = segmentPoints.toList(),
                lengthM = segmentLengthM,
                speedMps = meanSpeed,
                rms = rms,
                roughness = roughness,
                score = score(roughness),
                quality = quality(roughness),
            ),
        )
    }

    private fun resetSegment() {
        segmentPoints.clear()
        segmentLengthM = 0.0
        segmentSumSquares = 0.0
        segmentSamples = 0
        segmentSpeedSum = 0.0
        segmentSpeedCount = 0
    }

    fun roughness(rms: Double, speedMps: Double): Double =
        rms * (config.referenceSpeedMps / max(speedMps, config.minSegmentSpeedMps)).pow(config.speedExponent)

    fun score(roughness: Double): Int {
        val fraction = (roughness - config.scoreBestRoughness) /
            (config.scoreWorstRoughness - config.scoreBestRoughness)
        return (100 * (1 - fraction)).roundToInt().coerceIn(0, 100)
    }

    fun quality(roughness: Double): RoadQuality = when {
        roughness < config.goodBelow -> RoadQuality.GOOD
        roughness < config.fairBelow -> RoadQuality.FAIR
        roughness < config.poorBelow -> RoadQuality.POOR
        else -> RoadQuality.BAD
    }

    /**
     * A jolt opens a short window. A speed bump lifts the whole car: the low-passed signal stays high
     * for a while. A pothole is a sharp spike that the low-pass filter mostly removes.
     */
    private fun detectEvent(tNs: Long, dtNs: Long, highPass: Double) {
        val fix = lastFix
        val fixAgeNs = tNs - lastFixNs
        if (fix == null || fixAgeNs !in 0..config.maxExtrapolationNs) {
            eventCandidate = null
            return
        }
        val candidate = eventCandidate ?: run {
            if (!speedMps.isFinite() || speedMps < config.minEventSpeedMps) return
            if (tNs < refractoryUntilNs) return
            if (abs(highPass) < config.potholeHighPass && abs(lowPass) < config.bumpLowPass) return
            // Anchor the event when the jolt starts, before a newer fix can move its position.
            val position = if (bearingDeg.isNaN() || fixAgeNs == 0L) fix
                else Geo.destination(fix, bearingDeg, speedMps * fixAgeNs / 1e9)
            EventCandidate(tNs, position, speedMps).also { eventCandidate = it }
        }
        candidate.maxHighPass = max(candidate.maxHighPass, abs(highPass))
        candidate.maxLowPass = max(candidate.maxLowPass, abs(lowPass))
        if (abs(lowPass) >= config.bumpLowPass) {
            val aboveSinceNs = candidate.lowPassAboveSinceNs?.takeIf { dtNs < MAX_SAMPLE_GAP_NS } ?: tNs
            candidate.lowPassAboveSinceNs = aboveSinceNs
            candidate.longestLowPassAboveNs = max(candidate.longestLowPassAboveNs, tNs - aboveSinceNs)
        } else {
            candidate.lowPassAboveSinceNs = null
        }
        if (tNs - candidate.startNs < config.eventWindowNs) return

        val type = when {
            candidate.longestLowPassAboveNs >= config.bumpMinDurationNs && candidate.speedMps <= config.maxBumpSpeedMps ->
                EventType.SPEED_BUMP
            candidate.maxHighPass >= config.potholeHighPass -> EventType.POTHOLE
            else -> null
        }
        eventCandidate = null
        if (type == null) return
        refractoryUntilNs = tNs + config.refractoryNs
        val peak = if (type == EventType.SPEED_BUMP) candidate.maxLowPass else candidate.maxHighPass
        onEvent(RoadEvent(type, candidate.startNs, candidate.position, candidate.speedMps, peak))
    }

    private class EventCandidate(val startNs: Long, val position: GeoPoint, val speedMps: Double) {
        var maxHighPass = 0.0
        var maxLowPass = 0.0
        var lowPassAboveSinceNs: Long? = null
        var longestLowPassAboveNs = 0L
    }

    private companion object {
        const val MAX_SAMPLE_GAP_NS = 100_000_000L
    }
}
