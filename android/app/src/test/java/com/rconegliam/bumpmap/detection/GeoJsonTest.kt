package com.rconegliam.bumpmap.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

class GeoJsonTest {
    @Test
    fun segmentFeatureUsesDotDecimalsInPortuguese() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        try {
            val json = GeoJson.feature(
                RoadSegment(
                    points = listOf(GeoPoint(-23.5, -46.8), GeoPoint(-23.5002, -46.8001)),
                    lengthM = 25.0,
                    speedMps = 10.0,
                    rms = 0.5,
                    roughness = 0.6123,
                    score = 86,
                    quality = RoadQuality.FAIR,
                ),
            )
            assertEquals(
                """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-46.8,-23.5],[-46.8001,-23.5002]]},""" +
                    """"properties":{"kind":"segment","quality":"fair","score":86,"roughness":0.61,"speed_kmh":36.0}}""",
                json,
            )
            assertFalse(json.contains("0,61"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun eventFeatureAndCollection() {
        val json = GeoJson.feature(RoadEvent(EventType.POTHOLE, 1, GeoPoint(-23.5, -46.8), 5.0, 7.456))
        assertEquals(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[-46.8,-23.5]},""" +
                """"properties":{"kind":"event","type":"pothole","peak":7.46,"speed_kmh":18.0}}""",
            json,
        )
        assertEquals("""{"type":"FeatureCollection","features":[a,b]}""", GeoJson.collection(listOf("a", "b")))
    }
}
