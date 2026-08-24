package com.lightmeter.app.filmpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmResponseCurveTest {
    @Test
    fun curvePreservesLinearMiddleTonesBeforeShoulderAndToe() {
        assertEquals(0.045, luminance(-2.0), 0.0001)
        assertEquals(0.09, luminance(-1.0), 0.0001)
        assertEquals(0.18, luminance(0.0), 0.0001)
        assertEquals(0.36, luminance(1.0), 0.0001)
        assertEquals(0.72, luminance(2.0), 0.0001)
    }

    @Test
    fun shoulderAndToeOnlyCompressOutsideLinearCore() {
        assertEquals(0.0225, luminance(-3.0), 0.0001)
        assertEquals(
            0.72 + (1.0 - 0.72) * smoothstep(1.0 / 3.0),
            luminance(3.0),
            0.0001,
        )
    }

    @Test
    fun curveIsFiniteBoundedAndMonotonic() {
        val samples = (-100..120).map { step ->
            luminance(step / 20.0)
        }

        assertTrue(samples.all { it.isFinite() && it in 0.0..1.0 })
        samples.zipWithNext().forEach { (left, right) ->
            assertTrue(right >= left)
        }
    }

    @Test
    fun curveSoftlyExtendsBeyondDetailLossBoundaries() {
        assertTrue(luminance(-3.0) < luminance(-2.0))
        assertTrue(luminance(-3.0) > 0.0)
        assertTrue(luminance(4.0) > luminance(3.0))
        assertTrue(luminance(4.0) < 1.0)
        assertEquals(0.0, luminance(-4.0), 0.0001)
        assertEquals(1.0, luminance(5.0), 0.0001)
    }

    private fun luminance(deltaEv: Double): Double {
        return FilmResponseCurve.targetLuminance(
            deltaEv = deltaEv,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
        )
    }

    private fun smoothstep(value: Double): Double {
        return value * value * (3.0 - 2.0 * value)
    }
}
