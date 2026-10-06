package com.rconegliam.bumpmap.recorder

import kotlin.math.max
import kotlin.math.sqrt

object SignalMath {
    /**
     * Vertical acceleration without gravity (m/s²): the raw accelerometer vector projected on the
     * gravity direction, minus the gravity magnitude. Works for any phone orientation.
     */
    fun verticalLinear(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): Double {
        val gNorm = sqrt((gx * gx + gy * gy + gz * gz).toDouble())
        if (gNorm < 1e-6) return 0.0
        return (ax * gx + ay * gy + az * gz) / gNorm - gNorm
    }
}

/** Root mean square of the values received in the last [windowNs] nanoseconds. */
class RollingRms(private val windowNs: Long) {
    private val times = ArrayDeque<Long>()
    private val squares = ArrayDeque<Double>()
    private var sum = 0.0

    fun add(tNs: Long, value: Double) {
        val square = value * value
        times.addLast(tNs)
        squares.addLast(square)
        sum += square
        while (tNs - times.first() > windowNs) {
            times.removeFirst()
            sum -= squares.removeFirst()
        }
    }

    val value: Double
        get() = if (squares.isEmpty()) 0.0 else sqrt(max(sum, 0.0) / squares.size)
}
