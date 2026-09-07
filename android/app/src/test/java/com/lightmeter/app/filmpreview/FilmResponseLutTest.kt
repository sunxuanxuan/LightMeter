package com.lightmeter.app.filmpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmResponseLutTest {
    @Test
    fun everyChannelIsMonotonicAcrossTheSupportedExposureRange() {
        val lut = createLut(NegativeFilmLooks.KODAK_GOLD_200)
        val exposureSteps = (0..420).map { index ->
            FilmResponseLut.MIN_EXPOSURE_EV + index / 30.0
        }

        listOf(lut::sampleRed, lut::sampleGreen, lut::sampleBlue).forEach { sample ->
            exposureSteps.zipWithNext().forEach { (left, right) ->
                assertTrue(sample(right) >= sample(left))
            }
        }
    }

    @Test
    fun channelsUseIndependentResponseCurves() {
        val look = NegativeFilmLooks.NEUTRAL.copy(
            responseGamma = FilmRgbVector(red = 0.5, green = 1.0, blue = 1.5),
        )
        val lut = createLut(look)

        assertTrue(lut.sampleRed(2.0) < lut.sampleGreen(2.0))
        assertTrue(lut.sampleGreen(2.0) < lut.sampleBlue(2.0))
        assertEquals(0.18, lut.sampleRed(0.0), GRAY_ANCHOR_TOLERANCE)
        assertEquals(0.18, lut.sampleGreen(0.0), GRAY_ANCHOR_TOLERANCE)
        assertEquals(0.18, lut.sampleBlue(0.0), GRAY_ANCHOR_TOLERANCE)
    }

    private fun createLut(look: NegativeFilmLookProfile): FilmResponseLut {
        return FilmResponseLut.create(
            filmLook = look,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
        )
    }

    private companion object {
        const val GRAY_ANCHOR_TOLERANCE = 0.001
    }
}
