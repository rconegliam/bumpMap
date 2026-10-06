package com.rconegliam.bumpmap.recorder

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class SignalMathTest {
    private val g = 9.81f

    @Test
    fun phoneFlatAtRestHasNoVerticalAcceleration() {
        assertEquals(0.0, SignalMath.verticalLinear(0f, 0f, g, 0f, 0f, g), 1e-6)
    }

    @Test
    fun phoneFlatGoingUp() {
        assertEquals(2.0, SignalMath.verticalLinear(0f, 0f, g + 2f, 0f, 0f, g), 1e-5)
    }

    @Test
    fun phoneTiltedFortyFiveDegrees() {
        val c = (g / sqrt(2.0)).toFloat()
        val up = (1.5 / sqrt(2.0)).toFloat()
        // Same vertical bump of 1.5 m/s² seen by a phone tilted 45° in its holder.
        assertEquals(1.5, SignalMath.verticalLinear(0f, c + up, c + up, 0f, c, c), 1e-4)
    }

    @Test
    fun sidewaysAccelerationIsIgnored() {
        assertEquals(0.0, SignalMath.verticalLinear(3f, 0f, g, 0f, 0f, g), 1e-6)
    }

    @Test
    fun zeroGravityReturnsZero() {
        assertEquals(0.0, SignalMath.verticalLinear(1f, 2f, 3f, 0f, 0f, 0f), 0.0)
    }
}
