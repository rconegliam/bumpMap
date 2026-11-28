package com.rconegliam.bumpmap.api

import kotlin.test.Test
import kotlin.test.assertEquals

class MapScoringTest {
    @Test
    fun usesBaselineWeightsRoundingAndSpeedCap() {
        assertEquals(0L, mapPenaltyPoints(100, 60.0, 0, 0))
        assertEquals(1L, mapPenaltyPoints(98, 75.0, 0, 0))
        assertEquals(10L, mapPenaltyPoints(100, 0.0, 0, 0))
        assertEquals(20L, mapPenaltyPoints(100, 60.0, 1, 0))
        assertEquals(12L, mapPenaltyPoints(100, 60.0, 0, 1))
        assertEquals(Int.MAX_VALUE * 32L, mapPenaltyPoints(100, 60.0, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun usesExclusiveQualityCutoffs() {
        assertEquals(
            listOf("good", "fair", "fair", "poor", "poor", "bad"),
            listOf(7L, 8L, 19L, 20L, 39L, 40L).map(::mapQuality),
        )
    }
}
