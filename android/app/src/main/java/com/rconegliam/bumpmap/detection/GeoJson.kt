package com.rconegliam.bumpmap.detection

import kotlin.math.roundToLong

/** Minimal GeoJSON writer; numbers always use '.' whatever the device locale. */
object GeoJson {
    fun feature(segment: RoadSegment): String {
        val coordinates = segment.points.joinToString(",") { "[${coord(it.lon)},${coord(it.lat)}]" }
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},""" +
            """"properties":{"kind":"segment","quality":"${segment.quality.code}","score":${segment.score},""" +
            """"roughness":${round2(segment.roughness)},"speed_kmh":${round2(segment.speedMps * 3.6)}}}"""
    }

    fun feature(event: RoadEvent): String =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":""" +
            """[${coord(event.position.lon)},${coord(event.position.lat)}]},""" +
            """"properties":{"kind":"event","type":"${event.type.code}","peak":${round2(event.peak)},""" +
            """"speed_kmh":${round2(event.speedMps * 3.6)}}}"""

    fun collection(features: List<String>): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun coord(value: Double): String = ((value * 1e6).roundToLong() / 1e6).toString()

    private fun round2(value: Double): String = ((value * 100).roundToLong() / 100.0).toString()
}
