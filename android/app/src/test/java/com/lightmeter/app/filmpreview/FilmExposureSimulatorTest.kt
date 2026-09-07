package com.lightmeter.app.filmpreview

import com.lightmeter.app.metering.ExposureMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

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
    fun filmGrainIsDeterministicForTheSameProfileAndCoordinates() {
        val source = IntArray(64) { argb(alpha = 255, value = 118) }

        val first = render(
            source = source,
            sourceWidth = 8,
            filmLook = NegativeFilmLooks.KODAK_DISPOSABLE_800,
        )
        val second = render(
            source = source,
            sourceWidth = 8,
            filmLook = NegativeFilmLooks.KODAK_DISPOSABLE_800,
        )

        assertTrue(first.contentEquals(second))
        assertTrue(first.map { it and 0x00FFFFFF }.distinct().size > 1)
    }

    @Test
    fun filmGrainChangesForADifferentCapturedFrame() {
        val source = IntArray(64) { argb(alpha = 255, value = 118) }
        val firstMap = uniformExposureMap(width = 8, height = 8, timestampNs = 1L)
        val secondMap = uniformExposureMap(width = 8, height = 8, timestampNs = 2L)

        val first = render(
            source = source,
            sourceWidth = 8,
            exposureMap = firstMap,
            filmLook = NegativeFilmLooks.KODAK_DISPOSABLE_800,
        )
        val second = render(
            source = source,
            sourceWidth = 8,
            exposureMap = secondMap,
            filmLook = NegativeFilmLooks.KODAK_DISPOSABLE_800,
        )

        assertTrue(!first.contentEquals(second))
    }

    @Test
    fun filmProfilesProduceDifferentColorResponseAwayFromMiddleGray() {
        val source = IntArray(4) { argb(alpha = 255, value = 118) }
        val exposureMap = ExposureMap(
            width = 2,
            height = 2,
            pixelEv100 = FloatArray(4) { -2f },
            cameraSettingEv100 = 0.0,
            timestampNs = 1L,
        )

        val gold = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = NegativeFilmLooks.KODAK_GOLD_200.copy(grainAmount = 0.0),
        )
        val superia = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = NegativeFilmLooks.FUJI_SUPERIA_XTRA_400.copy(grainAmount = 0.0),
        )

        assertTrue(!gold.contentEquals(superia))
        assertTrue(red(gold.first()) > blue(gold.first()))
    }

    @Test
    fun toneGammaChangesMidtoneContrast() {
        val source = IntArray(4) { argb(alpha = 255, value = 118) }
        val exposureMap = ExposureMap(
            width = 2,
            height = 2,
            pixelEv100 = FloatArray(4) { 1f },
            cameraSettingEv100 = 0.0,
            timestampNs = 1L,
        )

        val lowContrast = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = NegativeFilmLooks.NEUTRAL.copy(toneGamma = 0.8),
        )
        val highContrast = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = NegativeFilmLooks.NEUTRAL.copy(toneGamma = 1.2),
        )

        assertTrue(red(highContrast.first()) > red(lowContrast.first()))
    }

    @Test
    fun colorMatrixChangesNormallyExposedColoredPixels() {
        val source = IntArray(4) { 0xFFB06040.toInt() }
        val exposureMap = uniformExposureMap(width = 2, height = 2, timestampNs = 1L)

        val neutral = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = NegativeFilmLooks.NEUTRAL,
        )
        val gold = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = NegativeFilmLooks.KODAK_GOLD_200.copy(grainAmount = 0.0),
        )

        assertTrue(!neutral.contentEquals(gold))
        assertTrue(
            red(gold.first()) - blue(gold.first()) >
                red(neutral.first()) - blue(neutral.first()),
        )
    }

    @Test
    fun independentChannelResponseIsNotRenormalizedToTheSharedTargetLuminance() {
        val source = IntArray(4) { argb(alpha = 255, value = 118) }
        val exposureMap = ExposureMap(
            width = 2,
            height = 2,
            pixelEv100 = FloatArray(4) { 1f },
            cameraSettingEv100 = 0.0,
            timestampNs = 1L,
        )
        val asymmetricLook = NegativeFilmLooks.NEUTRAL.copy(
            responseGamma = FilmRgbVector(red = 0.2, green = 0.2, blue = 1.5),
        )

        val result = render(
            source = source,
            sourceWidth = 2,
            exposureMap = exposureMap,
            filmLook = asymmetricLook,
        ).first()
        val outputLuminance = linearLuminance(result)

        assertTrue(blue(result) > red(result))
        assertTrue(outputLuminance < 0.28)
    }

    @Test
    fun iso800GrainHasVisibleRangeAtReferenceWidth() {
        val source = IntArray(1080) { argb(alpha = 255, value = 118) }

        val result = render(
            source = source,
            sourceWidth = 1080,
            filmLook = NegativeFilmLooks.KODAK_DISPOSABLE_800,
        )
        val redValues = result.map(::red)

        assertTrue(redValues.max() - redValues.min() >= 8)
    }

    private fun render(source: IntArray): IntArray {
        return render(
            source = source,
            sourceWidth = source.size,
            filmLook = NegativeFilmLooks.NEUTRAL,
        )
    }

    private fun render(
        source: IntArray,
        sourceWidth: Int,
        exposureMap: ExposureMap? = null,
        filmLook: NegativeFilmLookProfile,
    ): IntArray {
        return FilmExposureSimulator.renderPixels(
            sourcePixels = source,
            exposureMap = exposureMap,
            cameraSettingEv100 = 0.0,
            calibrationOffset = 0.0,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            sourceWidth = sourceWidth,
            filmLook = filmLook,
        )
    }

    private fun argb(alpha: Int, value: Int): Int {
        return (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }

    private fun uniformExposureMap(width: Int, height: Int, timestampNs: Long): ExposureMap {
        return ExposureMap(
            width = width,
            height = height,
            pixelEv100 = FloatArray(width * height),
            cameraSettingEv100 = 0.0,
            timestampNs = timestampNs,
        )
    }

    private fun red(argb: Int): Int = (argb ushr 16) and 0xFF

    private fun blue(argb: Int): Int = argb and 0xFF

    private fun linearLuminance(argb: Int): Double {
        return 0.2126 * srgbByteToLinear(red(argb)) +
            0.7152 * srgbByteToLinear((argb ushr 8) and 0xFF) +
            0.0722 * srgbByteToLinear(blue(argb))
    }

    private fun srgbByteToLinear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
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
