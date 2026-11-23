package com.rconegliam.bumpmap.recorder

import com.rconegliam.bumpmap.detection.RoadEvent

enum class MarkType(val code: String) {
    SPEED_BUMP("bump"),
    POTHOLE("pothole"),
    ROUGH("rough"),
}

/**
 * Rows of a recording file. See docs/recording-format.md.
 * Kotlin number-to-string conversion always uses '.' as decimal separator, whatever the device locale.
 */
object CsvRows {
    const val HEADER = "type,t_ns,v1,v2,v3,v4,v5,v6"

    fun meta(key: String, value: Any?): String = "# $key=$value"

    fun vector(type: Char, tNs: Long, x: Float, y: Float, z: Float): String = "$type,$tNs,$x,$y,$z"

    fun location(
        tNs: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speedMps: Float,
        bearing: Float,
        accuracyM: Float,
    ): String = "l,$tNs,$latitude,$longitude,$altitude,$speedMps,$bearing,$accuracyM"

    fun mark(tNs: Long, type: MarkType): String = "m,$tNs,${type.code}"

    fun event(event: RoadEvent): String =
        "e,${event.tNs},${event.type.code},${event.peak},${event.position.lat},${event.position.lon}"
}
