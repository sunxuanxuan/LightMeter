package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightmeter.app.metering.ExposureMap
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
            exposureMap = ExposureMap(
                width = 2,
                height = 2,
                pixelEv100 = floatArrayOf(0f, 0f, 0f, 0f),
                cameraSettingEv100 = 0.0,
                timestampNs = 1L,
            ),
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

    @Test
    fun gpuMatchesCpuForIndependentChannelResponseLuts() {
        val sourcePixels = intArrayOf(
            0xFFB06040.toInt(),
            0xFF4080C0.toInt(),
            0xFF60A050.toInt(),
            0xFFD0B080.toInt(),
        )
        val source = Bitmap.createBitmap(
            sourcePixels,
            2,
            2,
            Bitmap.Config.ARGB_8888,
        )
        val exposureMap = ExposureMap(
            width = 2,
            height = 2,
            pixelEv100 = floatArrayOf(-2f, -0.5f, 1f, 3f),
            cameraSettingEv100 = 0.0,
            timestampNs = 7L,
        )
        val filmLook = NegativeFilmLooks.KODAK_GOLD_200.copy(grainAmount = 0.0)

        val gpu = GpuFilmExposureRenderer.render(
            source = source,
            exposureMap = exposureMap,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            filmLook = filmLook,
        )
        val cpuPixels = FilmExposureSimulator.renderPixels(
            sourcePixels = sourcePixels,
            exposureMap = exposureMap,
            cameraSettingEv100 = 0.0,
            calibrationOffset = 0.0,
            referenceEv100 = 0.0,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            sourceWidth = 2,
            filmLook = filmLook,
        )
        val gpuPixels = IntArray(sourcePixels.size)
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
