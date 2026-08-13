package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GpuFilmExposureRendererTest {
    @Test
    fun gpuMatchesCpuWithoutChangingDimensionsOrPixelOrder() {
        val sourcePixels = intArrayOf(
            gray(48),
            gray(96),
            gray(144),
            gray(208),
        )
        val source = Bitmap.createBitmap(
            sourcePixels,
            2,
            2,
            Bitmap.Config.ARGB_8888,
        )

        val gpu = GpuFilmExposureRenderer.render(
            source = source,
            cameraSettingEv100 = 0.0,
            calibrationOffset = 0.0,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
        )
        val cpuPixels = FilmExposureSimulator.renderPixels(
            sourcePixels = sourcePixels,
            cameraSettingEv100 = 0.0,
            calibrationOffset = 0.0,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
        )
        val gpuPixels = IntArray(4)
        gpu.getPixels(gpuPixels, 0, 2, 0, 0, 2, 2)

        assertEquals(source.width, gpu.width)
        assertEquals(source.height, gpu.height)
        gpuPixels.indices.forEach { index ->
            assertChannelNear(cpuPixels[index], gpuPixels[index], shift = 16)
            assertChannelNear(cpuPixels[index], gpuPixels[index], shift = 8)
            assertChannelNear(cpuPixels[index], gpuPixels[index], shift = 0)
        }
    }

    @Test
    fun gpuExposureCompensationMatchesCpu() {
        val sourcePixels = intArrayOf(
            gray(48),
            gray(96),
            gray(144),
            gray(208),
        )
        val source = Bitmap.createBitmap(
            sourcePixels,
            2,
            2,
            Bitmap.Config.ARGB_8888,
        )

        val gpu = GpuFilmExposureRenderer.renderExposureCompensation(
            source = source,
            exposureCompensation = 1.0,
        )
        val cpuPixels = FilmExposureSimulator.renderExposureCompensationPixels(
            sourcePixels = sourcePixels,
            exposureCompensation = 1.0,
        )
        val gpuPixels = IntArray(4)
        gpu.getPixels(gpuPixels, 0, 2, 0, 0, 2, 2)

        gpuPixels.indices.forEach { index ->
            assertChannelNear(cpuPixels[index], gpuPixels[index], shift = 16)
            assertChannelNear(cpuPixels[index], gpuPixels[index], shift = 8)
            assertChannelNear(cpuPixels[index], gpuPixels[index], shift = 0)
        }
    }

    private fun assertChannelNear(expected: Int, actual: Int, shift: Int) {
        val expectedChannel = (expected ushr shift) and 0xFF
        val actualChannel = (actual ushr shift) and 0xFF
        assertTrue(
            "Expected channel $expectedChannel, got $actualChannel",
            abs(expectedChannel - actualChannel) <= CHANNEL_TOLERANCE,
        )
    }

    private fun gray(value: Int): Int {
        return (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }

    private companion object {
        const val CHANNEL_TOLERANCE = 3
    }
}
