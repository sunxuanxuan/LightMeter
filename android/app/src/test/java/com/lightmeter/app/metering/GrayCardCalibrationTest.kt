package com.lightmeter.app.metering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayCardCalibrationTest {
    @Test
    fun stableSamplesProduceMedianOffset() {
        val samples = listOf(
            10.1, 10.0, 10.1, 10.0, 10.1, 10.0,
            10.1, 10.0, 10.1, 10.0, 10.1, 10.0,
        )

        val result = GrayCardCalibration.estimate(
            referenceEv100 = 10.5,
            measuredEv100Samples = samples,
        )

        assertTrue(result is GrayCardCalibrationResult.Success)
        val estimate = (result as GrayCardCalibrationResult.Success).estimate
        assertEquals(0.45, estimate.offset, 1e-9)
        assertEquals(10.05, estimate.measuredEv100, 1e-9)
    }

    @Test
    fun unstableSamplesAreRejected() {
        val result = GrayCardCalibration.estimate(
            referenceEv100 = 10.0,
            measuredEv100Samples = listOf(
                9.5, 10.5, 9.4, 10.6, 9.3, 10.7,
                9.2, 10.8, 9.1, 10.9, 9.0, 11.0,
            ),
        )

        assertTrue(result is GrayCardCalibrationResult.Failure)
    }

    @Test
    fun implausibleOffsetIsRejected() {
        val result = GrayCardCalibration.estimate(
            referenceEv100 = 14.0,
            measuredEv100Samples = List(GrayCardCalibration.REQUIRED_SAMPLE_COUNT) { 10.0 },
        )

        assertTrue(result is GrayCardCalibrationResult.Failure)
    }
}
