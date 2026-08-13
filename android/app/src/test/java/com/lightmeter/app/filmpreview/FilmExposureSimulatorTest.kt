package com.lightmeter.app.filmpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmExposureSimulatorTest {
    @Test
    fun fullResolutionCpuPathPreservesPixelCountAndAlpha() {
        val source = intArrayOf(
            argb(alpha = 255, value = 64),
            argb(alpha = 192, value = 96),
            argb(alpha = 128, value = 128),
            argb(alpha = 64, value = 192),
        )

        val result = render(source)

        assertEquals(source.size, result.size)
        source.indices.forEach { index ->
            assertEquals(source[index] ushr 24, result[index] ushr 24)
        }
    }

    @Test
    fun adjacentLuminanceDetailIsNotFlattenedByRiskMapResolution() {
        val source = intArrayOf(
            argb(alpha = 255, value = 72),
            argb(alpha = 255, value = 96),
            argb(alpha = 255, value = 128),
            argb(alpha = 255, value = 176),
        )

        val outputValues = render(source).map { it and 0xFF }

        outputValues.zipWithNext().forEach { (left, right) ->
            assertTrue("Expected monotonic per-pixel detail: $outputValues", right > left)
        }
    }

    @Test
    fun middleGrayRemainsNearMiddleGrayAnchor() {
        val middleGraySrgb = 118

        val result = render(
            intArrayOf(argb(alpha = 255, value = middleGraySrgb)),
        ).single() and 0xFF

        assertTrue("Expected $middleGraySrgb, got $result", kotlin.math.abs(result - middleGraySrgb) <= 2)
    }

    private fun render(source: IntArray): IntArray {
        return FilmExposureSimulator.renderPixels(
            sourcePixels = source,
            cameraSettingEv100 = 0.0,
            calibrationOffset = 0.0,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
        )
    }

    private fun argb(alpha: Int, value: Int): Int {
        return (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
}
