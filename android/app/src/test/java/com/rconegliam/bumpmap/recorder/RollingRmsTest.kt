package com.rconegliam.bumpmap.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RollingRmsTest {
    @Test
    fun emptyIsZero() {
        assertEquals(0.0, RollingRms(1_000L).value, 0.0)
    }

    @Test
    fun rmsOfConstantMagnitude() {
        val rms = RollingRms(1_000L)
        rms.add(0, 2.0)
        rms.add(10, -2.0)
        assertEquals(2.0, rms.value, 1e-9)
    }

    @Test
    fun oldValuesLeaveTheWindow() {
        val rms = RollingRms(windowNs = 100L)
        rms.add(0, 10.0)
        rms.add(50, 10.0)
        rms.add(200, 1.0)
        assertEquals(1.0, rms.value, 1e-9)
    }
}
