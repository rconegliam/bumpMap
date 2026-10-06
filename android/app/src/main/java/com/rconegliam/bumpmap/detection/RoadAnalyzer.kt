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

    private val segmentPoints = ArrayList<GeoPoint>()
    private var segmentLengthM = 0.0
    private var segmentSumSquares = 0.0
    private var segmentSamples = 0
    private var segmentSpeedSum = 0.0
    private var segmentSpeedCount = 0

    private val lowPassRc = 1.0 / (2 * PI * config.lowPassHz)
    private var lowPass = 0.0
    private var lastSampleNs = Long.MIN_VALUE

    private var candidateStartNs = NO_CANDIDATE
    private var candidateMaxHighPass = 0.0
    private var candidateMaxLowPass = 0.0
    private var candidateLowPassAboveNs = 0L
    private var refractoryUntilNs = Long.MIN_VALUE

    fun onVertical(tNs: Long, verticalAcc: Double) {
        val dtNs = if (lastSampleNs == Long.MIN_VALUE) 0L else (tNs - lastSampleNs).coerceIn(0L, MAX_SAMPLE_GAP_NS)
        lastSampleNs = tNs
        val dtS = dtNs / 1e9
        val alpha = if (dtNs == 0L) 1.0 else dtS / (lowPassRc + dtS)
        lowPass += alpha * (verticalAcc - lowPass)
        val highPass = verticalAcc - lowPass

        segmentSumSquares += verticalAcc * verticalAcc
        segmentSamples++
        detectEvent(tNs, dtNs, highPass)
    }

    /** Pass NaN for values the fix does not have. */
    fun onLocation(tNs: Long, lat: Double, lon: Double, speedMps: Double, bearingDeg: Double, accuracyM: Double) {
        val point = GeoPoint(lat, lon)
        if (accuracyM.isNaN() || accuracyM > config.maxAccuracyM) {
            lastFix = null
            this.speedMps = Double.NaN
            resetSegment(null)
            return
        }
        val previous = lastFix
        val connected = previous != null && tNs - lastFixNs in 1..config.maxFixGapNs
        val distance = if (connected) Geo.distanceM(previous!!, point) else 0.0
        val speed = when {
            !speedMps.isNaN() -> speedMps
            connected -> distance / ((tNs - lastFixNs) / 1e9)
            else -> Double.NaN
        }
        this.speedMps = speed
        this.bearingDeg = bearingDeg
        lastFix = point
        lastFixNs = tNs

        if (!connected) {
            resetSegment(point)
            return
        }
        segmentPoints.add(point)
        segmentLengthM += distance
        if (!speed.isNaN()) {
            segmentSpeedSum += speed
            segmentSpeedCount++
        }
        if (segmentLengthM >= config.segmentLengthM) {
            closeSegment()
            resetSegment(point)
        }
    }

    private fun closeSegment() {
        if (segmentSpeedCount == 0 || segmentSamples < config.minSegmentSamples) return
        val meanSpeed = segmentSpeedSum / segmentSpeedCount
        if (meanSpeed < config.minSegmentSpeedMps) return
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

    private fun resetSegment(start: GeoPoint?) {
        segmentPoints.clear()
        start?.let { segmentPoints.add(it) }
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
        if (candidateStartNs == NO_CANDIDATE) {
            val moving = !speedMps.isNaN() && speedMps >= config.minEventSpeedMps
            if (!moving || tNs < refractoryUntilNs) return
            if (abs(highPass) < config.potholeHighPass && abs(lowPass) < config.bumpLowPass) return
            candidateStartNs = tNs
            candidateMaxHighPass = 0.0
            candidateMaxLowPass = 0.0
            candidateLowPassAboveNs = 0L
        }
        candidateMaxHighPass = max(candidateMaxHighPass, abs(highPass))
        candidateMaxLowPass = max(candidateMaxLowPass, abs(lowPass))
        if (abs(lowPass) >= config.bumpLowPass) candidateLowPassAboveNs += dtNs
        if (tNs - candidateStartNs < config.eventWindowNs) return

        val type = when {
            candidateLowPassAboveNs >= config.bumpMinDurationNs && speedMps <= config.maxBumpSpeedMps ->
                EventType.SPEED_BUMP
            candidateMaxHighPass >= config.potholeHighPass -> EventType.POTHOLE
            else -> null
        }
        val eventNs = candidateStartNs
        candidateStartNs = NO_CANDIDATE
        val position = positionAt(eventNs)
        if (type == null || position == null) return
        refractoryUntilNs = tNs + config.refractoryNs
        val peak = if (type == EventType.SPEED_BUMP) candidateMaxLowPass else candidateMaxHighPass
        onEvent(RoadEvent(type, eventNs, position, speedMps, peak))
    }

    /** Last GPS fix moved forward along the bearing, since GPS only arrives once per second. */
    private fun positionAt(tNs: Long): GeoPoint? {
        val fix = lastFix ?: return null
        val dtNs = (tNs - lastFixNs).coerceIn(0L, config.maxExtrapolationNs)
        if (bearingDeg.isNaN() || speedMps.isNaN() || dtNs == 0L) return fix
        return Geo.destination(fix, bearingDeg, speedMps * dtNs / 1e9)
    }

    private companion object {
        const val NO_CANDIDATE = -1L
        const val MAX_SAMPLE_GAP_NS = 100_000_000L
    }
}
