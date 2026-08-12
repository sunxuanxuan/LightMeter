package com.lightmeter.app.metering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExposureRiskCalculatorTest {
    @Test
    fun classifiesHighlightShadowAndNormalPixelsAtThresholds() {
        val exposureMap = ExposureMap(
            width = 2,
            height = 2,
            pixelEv100 = floatArrayOf(4.0f, -3.0f, 0.0f, 5.0f),
            timestampNs = 1L,
        )

        val result = ExposureRiskCalculator.calculate(
            exposureMap = exposureMap,
            viewfinder = NormalizedMeteringRect.Full,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 4.0,
            shadowLatitudeStops = 3.0,
        )

        assertNotEquals(0, result.argb[0])
        assertNotEquals(0, result.argb[1])
        assertEquals(0, result.argb[2])
        assertNotEquals(0, result.argb[3])
        assertEquals(0.5, result.highlightRatio, 0.0001)
        assertEquals(0.25, result.shadowRatio, 0.0001)
    }

    @Test
    fun ignoresPixelsOutsideViewfinderWhenCalculatingRatios() {
        val exposureMap = ExposureMap(
            width = 2,
            height = 2,
            pixelEv100 = floatArrayOf(8.0f, 8.0f, -8.0f, -8.0f),
            timestampNs = 1L,
        )
        val rightHalf = NormalizedMeteringRect(
            left = 0.5,
            top = 0.0,
            right = 1.0,
            bottom = 1.0,
        )

        val result = ExposureRiskCalculator.calculate(
            exposureMap = exposureMap,
            viewfinder = rightHalf,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 4.0,
            shadowLatitudeStops = 3.0,
        )

        assertEquals(0, result.argb[0])
        assertNotEquals(0, result.argb[1])
        assertEquals(0, result.argb[2])
        assertNotEquals(0, result.argb[3])
        assertEquals(0.5, result.highlightRatio, 0.0001)
        assertEquals(0.5, result.shadowRatio, 0.0001)
    }

    @Test
    fun ignoresNonFiniteExposureValues() {
        val exposureMap = ExposureMap(
            width = 2,
            height = 1,
            pixelEv100 = floatArrayOf(Float.NaN, 5.0f),
            timestampNs = 1L,
        )

        val result = ExposureRiskCalculator.calculate(
            exposureMap = exposureMap,
            viewfinder = NormalizedMeteringRect.Full,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 4.0,
            shadowLatitudeStops = 3.0,
        )

        assertEquals(0, result.argb[0])
        assertNotEquals(0, result.argb[1])
        assertEquals(1.0, result.highlightRatio, 0.0001)
        assertEquals(0.0, result.shadowRatio, 0.0001)
    }

    @Test
    fun increasesOverlayOpacityWithRiskSeverity() {
        val result = ExposureRiskCalculator.calculate(
            exposureMap = ExposureMap(
                width = 6,
                height = 1,
                pixelEv100 = floatArrayOf(4.0f, 5.0f, 6.0f, -3.0f, -4.0f, -5.0f),
                timestampNs = 1L,
            ),
            viewfinder = NormalizedMeteringRect.Full,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 4.0,
            shadowLatitudeStops = 3.0,
        )

        val alpha = result.argb.map { it ushr 24 }
        assertEquals(listOf(0x33, 0x8D, 0xE6), alpha.take(3))
        assertEquals(listOf(0x33, 0x8D, 0xE6), alpha.takeLast(3))
        assertEquals(1.0, result.highlightRatio + result.shadowRatio, 0.0001)
    }

    @Test
    fun recalculatesFrozenRiskForExposureCompensation() {
        val exposureMap = ExposureMap(
            width = 2,
            height = 1,
            pixelEv100 = floatArrayOf(13.5f, 7.0f),
            timestampNs = 1L,
        )

        val neutral = ExposureRiskCalculator.calculate(
            exposureMap = exposureMap,
            viewfinder = NormalizedMeteringRect.Full,
            referenceEv100 = ExposureRiskCalculator.referenceEv100(
                frozenMeteredEv100 = 10.0,
                exposureCompensation = 0.0,
            ),
            highlightLatitudeStops = 4.0,
            shadowLatitudeStops = 3.0,
        )
        val plusOne = ExposureRiskCalculator.calculate(
            exposureMap = exposureMap,
            viewfinder = NormalizedMeteringRect.Full,
            referenceEv100 = ExposureRiskCalculator.referenceEv100(
                frozenMeteredEv100 = 10.0,
                exposureCompensation = 1.0,
            ),
            highlightLatitudeStops = 4.0,
            shadowLatitudeStops = 3.0,
        )

        assertEquals(0.0, neutral.highlightRatio, 0.0001)
        assertEquals(0.5, neutral.shadowRatio, 0.0001)
        assertEquals(0.5, plusOne.highlightRatio, 0.0001)
        assertEquals(0.0, plusOne.shadowRatio, 0.0001)
    }

    @Test
    fun warnsForClippedHighlightWhenFilmThresholdCannotBeMeasured() {
        val result = ExposureRiskCalculator.calculate(
            exposureMap = ExposureMap(
                width = 1,
                height = 1,
                pixelEv100 = floatArrayOf(2.4f),
                clippedHighlights = booleanArrayOf(true),
                timestampNs = 1L,
            ),
            viewfinder = NormalizedMeteringRect.Full,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 6.0,
            shadowLatitudeStops = 5.0,
        )

        assertNotEquals(0, result.argb[0])
        assertEquals(1.0, result.highlightRatio, 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSnapshotWithMismatchedFrameTimestamp() {
        ExposureSnapshot(
            exposureMap = ExposureMap(
                width = 1,
                height = 1,
                pixelEv100 = floatArrayOf(0.0f),
                timestampNs = 1L,
            ),
            meteredEv100 = 0.0,
            timestampNs = 2L,
            revision = 0L,
        )
    }
}
