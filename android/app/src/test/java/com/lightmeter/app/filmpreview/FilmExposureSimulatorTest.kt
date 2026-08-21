package com.lightmeter.app.filmpreview

import com.lightmeter.app.metering.ExposureMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmExposureSimulatorTest {
    @Test
    fun zeroExposureCompensationPreservesEveryPixel() {
        val source = intArrayOf(
            0xFF123456.toInt(),
            0xC0804020.toInt(),
            0x80010203.toInt(),
            0x40000000,
        )

        val result = FilmExposureSimulator.renderExposureCompensationPixels(
            sourcePixels = source,
            exposureCompensation = 0.0,
        )

        assertTrue(source.contentEquals(result))
        assertTrue(source !== result)
    }

    @Test
    fun positiveExposureCompensationBrightensLinearRgb() {
        val source = intArrayOf(0xFF406080.toInt())

        val result = FilmExposureSimulator.renderExposureCompensationPixels(
            sourcePixels = source,
            exposureCompensation = 1.0,
        ).single()

        assertChannelsChangedInDirection(source.single(), result, shouldIncrease = true)
    }

    @Test
    fun negativeExposureCompensationDarkensLinearRgb() {
        val source = intArrayOf(0xC080A0C0.toInt())

        val result = FilmExposureSimulator.renderExposureCompensationPixels(
            sourcePixels = source,
            exposureCompensation = -1.0,
        ).single()

        assertChannelsChangedInDirection(source.single(), result, shouldIncrease = false)
    }

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
    fun fullResolutionExposureMapControlsEachMatchingSourcePixel() {
        val source = intArrayOf(
            argb(alpha = 255, value = 118),
            argb(alpha = 255, value = 118),
        )
        val exposureMap = ExposureMap(
            width = 2,
            height = 1,
            pixelEv100 = floatArrayOf(-1f, 1f),
            cameraSettingEv100 = 0.0,
            timestampNs = 1L,
        )

        val output = FilmExposureSimulator.renderPixels(
            sourcePixels = source,
            exposureMap = exposureMap,
            cameraSettingEv100 = 0.0,
            calibrationOffset = 0.0,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            sourceWidth = 2,
        )

        assertTrue((output[0] and 0xFF) < (output[1] and 0xFF))
    }

    @Test
    fun middleGrayRemainsNearMiddleGrayAnchor() {
        val middleGraySrgb = 118

        val result = render(
            intArrayOf(argb(alpha = 255, value = middleGraySrgb)),
        ).single() and 0xFF

        assertTrue("Expected $middleGraySrgb, got $result", kotlin.math.abs(result - middleGraySrgb) <= 2)
    }

    @Test
    fun grainIsDeterministicAndStrengthensForUnderexposure() {
        val baseGrain = 0.012

        assertEquals(
            FilmGrainModel.noise(x = 17, y = 23),
            FilmGrainModel.noise(x = 17, y = 23),
            0.0,
        )
        assertEquals(
            baseGrain,
            FilmGrainModel.intensity(baseGrain, deltaEv = 0.0),
            1e-12,
        )
        assertEquals(
            baseGrain,
            FilmGrainModel.intensity(baseGrain, deltaEv = -1.0),
            1e-12,
        )
        assertEquals(
            baseGrain + 0.010,
            FilmGrainModel.intensity(baseGrain, deltaEv = -2.0),
            1e-12,
        )
        assertTrue(
            FilmGrainModel.intensity(baseGrain, deltaEv = -2.0) > baseGrain,
        )
        assertEquals(
            FilmGrainModel.MAX_GRAIN_INTENSITY,
            FilmGrainModel.intensity(baseGrain, deltaEv = -10.0),
            1e-12,
        )
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

    private fun assertChannelsChangedInDirection(
        source: Int,
        result: Int,
        shouldIncrease: Boolean,
    ) {
        assertEquals(source ushr 24, result ushr 24)
        listOf(16, 8, 0).forEach { shift ->
            val sourceChannel = (source ushr shift) and 0xFF
            val resultChannel = (result ushr shift) and 0xFF
            if (shouldIncrease) {
                assertTrue(
                    "$sourceChannel should increase, got $resultChannel",
                    resultChannel > sourceChannel,
                )
            } else {
                assertTrue(
                    "$sourceChannel should decrease, got $resultChannel",
                    resultChannel < sourceChannel,
                )
            }
        }
    }
}
