package com.rconegliam.bumpmap.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundingBoxTest {
    @Test
    fun acceptsOneDegreeOnBothAxesDespiteCoordinateRounding() {
        assertEquals(
            BoundingBox(-128.99, -16.99, -127.99, -15.99),
            BoundingBox.parse("-128.99,-16.99,-127.99,-15.99"),
        )
    }

    @Test
    fun rejectsSpansOverOneDegree() {
        assertNull(BoundingBox.parse("-128.99,-16.99,-127.989999,-15.99"))
        assertNull(BoundingBox.parse("-128.99,-16.99,-127.99,-15.989999"))
    }
}
