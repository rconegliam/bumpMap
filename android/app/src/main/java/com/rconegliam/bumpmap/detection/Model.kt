package com.rconegliam.bumpmap.detection

data class GeoPoint(val lat: Double, val lon: Double)

enum class RoadQuality(val code: String) {
    GOOD("good"),
    FAIR("fair"),
    POOR("poor"),
    BAD("bad"),
}

enum class EventType(val code: String) {
    POTHOLE("pothole"),
    SPEED_BUMP("speed_bump"),
}

/** A ~25 m stretch of road with its speed-normalized vertical shake. */
data class RoadSegment(
    val points: List<GeoPoint>,
    val lengthM: Double,
    val speedMps: Double,
    val rms: Double,
    val roughness: Double,
    val score: Int,
    val quality: RoadQuality,
)

data class RoadEvent(
    val type: EventType,
    val tNs: Long,
    val position: GeoPoint,
    val speedMps: Double,
    val peak: Double,
)

/**
 * Detection thresholds. They are first estimates and must be calibrated with real drives
 * (manual marks in the recordings are the ground truth).
 */
data class DetectionConfig(
    val segmentLengthM: Double = 25.0,
    val minSegmentSpeedMps: Double = 12 / 3.6,
    val minSegmentSamples: Int = 20,
    val maxAccuracyM: Double = 25.0,
    val maxFixGapNs: Long = 3_000_000_000L,
    /** Shake is scaled to what it would be at this speed: roughness = rms * (ref / speed)^exponent. */
    val referenceSpeedMps: Double = 40 / 3.6,
    val speedExponent: Double = 0.5,
    val goodBelow: Double = 0.6,
    val fairBelow: Double = 1.0,
    val poorBelow: Double = 1.6,
    /** Roughness that maps to score 100 and to score 0. */
    val scoreBestRoughness: Double = 0.3,
    val scoreWorstRoughness: Double = 2.5,
    val minEventSpeedMps: Double = 7 / 3.6,
    val maxBumpSpeedMps: Double = 50 / 3.6,
    /** Split between the slow body heave of a speed bump and the sharp jolt of a pothole. */
    val lowPassHz: Double = 2.0,
    val potholeHighPass: Double = 4.5,
    val bumpLowPass: Double = 1.2,
    val bumpMinDurationNs: Long = 150_000_000L,
    val eventWindowNs: Long = 600_000_000L,
    val refractoryNs: Long = 1_500_000_000L,
    val maxExtrapolationNs: Long = 2_000_000_000L,
)
