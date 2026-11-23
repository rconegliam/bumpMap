package com.rconegliam.bumpmap.recorder

import com.rconegliam.bumpmap.detection.EventType
import com.rconegliam.bumpmap.detection.GeoPoint
import com.rconegliam.bumpmap.detection.RoadEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class CsvRowsTest {
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        // Brazilian locale uses ',' as decimal separator; files must always use '.'.
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun vectorRowUsesDotDecimalSeparator() {
        assertEquals("a,123,0.5,-1.25,9.81", CsvRows.vector('a', 123L, 0.5f, -1.25f, 9.81f))
    }

    @Test
    fun locationRow() {
        assertEquals(
            "l,42,-23.5057,-46.879,750.5,13.9,90.0,4.0",
            CsvRows.location(42L, -23.5057, -46.879, 750.5, 13.9f, 90f, 4f),
        )
    }

    @Test
    fun locationRowWithMissingValues() {
        assertEquals(
            "l,1,-23.5,-46.6,NaN,NaN,NaN,NaN",
            CsvRows.location(1L, -23.5, -46.6, Double.NaN, Float.NaN, Float.NaN, Float.NaN),
        )
    }

    @Test
    fun markRow() {
        assertEquals("m,7,pothole", CsvRows.mark(7L, MarkType.POTHOLE))
        assertEquals("m,8,bump", CsvRows.mark(8L, MarkType.SPEED_BUMP))
    }

    @Test
    fun headerHasSixValueColumns() {
        assertEquals(8, CsvRows.HEADER.split(',').size)
    }

    @Test
    fun eventRow() {
        val event = RoadEvent(
            type = EventType.SPEED_BUMP,
            tNs = 42,
            position = GeoPoint(-23.5, -46.8),
            speedMps = 5.0,
            peak = 2.5,
        )
        assertEquals("e,42,speed_bump,2.5,-23.5,-46.8", CsvRows.event(event))
    }
}
