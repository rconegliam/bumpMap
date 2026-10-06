package com.rconegliam.bumpmap.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun distanceBetweenBarueriAndSaoPauloCentre() {
        val barueri = GeoPoint(-23.5057, -46.8792)
        val se = GeoPoint(-23.5505, -46.6333)
        assertEquals(25_500.0, Geo.distanceM(barueri, se), 1_000.0)
    }

    @Test
    fun destinationRoundTrip() {
        val start = GeoPoint(-23.53, -46.76)
        val moved = Geo.destination(start, 45.0, 100.0)
        assertEquals(100.0, Geo.distanceM(start, moved), 0.01)
    }
}
