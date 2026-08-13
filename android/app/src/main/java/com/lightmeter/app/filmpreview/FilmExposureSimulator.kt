package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.metering.ExposureMap
import java.util.stream.IntStream
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

internal object FilmExposureSimulator {
    fun render(
        source: Bitmap,
        exposureMap: ExposureMap,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): Bitmap {
        require(referenceEv100.isFinite())
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
        require(exposureMap.cameraSettingEv100.isFinite())

        val startedAtMs = SystemClock.elapsedRealtime()
        val gpuResult = runCatching {
            GpuFilmExposureRenderer.render(
                source,
                cameraSettingEv100 = exposureMap.cameraSettingEv100,
                calibrationOffset = exposureMap.calibrationOffset,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
            )
        }
        gpuResult.getOrNull()?.let { result ->
            logRender("gpu", source, result, startedAtMs)
            return result
        }
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "GPU film simulation failed; using CPU", gpuResult.exceptionOrNull())
        }

        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(
            sourcePixels,
            0,
            source.width,
            0,
            0,
            source.width,
            source.height,
        )
        val result = Bitmap.createBitmap(
            renderPixels(
                sourcePixels = sourcePixels,
                cameraSettingEv100 = exposureMap.cameraSettingEv100,
                calibrationOffset = exposureMap.calibrationOffset,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
            ),
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
        logRender("cpu", source, result, startedAtMs)
        return result
    }

    internal fun renderPixels(
        sourcePixels: IntArray,
        cameraSettingEv100: Double,
        calibrationOffset: Double,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): IntArray {
        require(cameraSettingEv100.isFinite())
        require(calibrationOffset.isFinite())
        require(referenceEv100.isFinite())
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)

        val outputPixels = IntArray(sourcePixels.size)
        val renderPixel: (Int) -> Unit = { index ->
            outputPixels[index] = simulatePixel(
                argb = sourcePixels[index],
                cameraSettingEv100 = cameraSettingEv100,
                calibrationOffset = calibrationOffset,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
            )
        }
        if (sourcePixels.size >= PARALLEL_PIXEL_THRESHOLD) {
            IntStream.range(0, sourcePixels.size).parallel().forEach(renderPixel)
        } else {
            sourcePixels.indices.forEach(renderPixel)
        }
        return outputPixels
    }

    private fun simulatePixel(
        argb: Int,
        cameraSettingEv100: Double,
        calibrationOffset: Double,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): Int {
        val red = SRGB_TO_LINEAR[(argb ushr 16) and 0xFF]
        val green = SRGB_TO_LINEAR[(argb ushr 8) and 0xFF]
        val blue = SRGB_TO_LINEAR[argb and 0xFF]
        val sourceLuminance = RED_LUMINANCE_WEIGHT * red +
            GREEN_LUMINANCE_WEIGHT * green +
            BLUE_LUMINANCE_WEIGHT * blue
        val pixelEv100 = cameraSettingEv100 +
            log2(sourceLuminance.coerceAtLeast(LUMINANCE_EPSILON) / TARGET_LUMINANCE) +
            calibrationOffset
        val targetLuminance = FilmResponseCurve.targetLuminance(
            deltaEv = pixelEv100 - referenceEv100,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
        )
        val gain = targetLuminance / sourceLuminance.coerceAtLeast(LUMINANCE_EPSILON)

        return (argb and -0x1000000) or
            (linearToByte(red * gain) shl 16) or
            (linearToByte(green * gain) shl 8) or
            linearToByte(blue * gain)
    }

    private fun linearToByte(value: Double): Int {
        val index = (
            value.coerceIn(0.0, 1.0) * (LINEAR_TO_SRGB.size - 1)
            ).roundToInt()
        return LINEAR_TO_SRGB[index]
    }

    private fun logRender(
        backend: String,
        source: Bitmap,
        result: Bitmap,
        startedAtMs: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "backend=%s source=%dx%d output=%dx%d durationMs=%d".format(
                backend,
                source.width,
                source.height,
                result.width,
                result.height,
                SystemClock.elapsedRealtime() - startedAtMs,
            ),
        )
    }

    private val SRGB_TO_LINEAR = DoubleArray(256) { byte ->
        val value = byte / 255.0
        if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }
    private val LINEAR_TO_SRGB = IntArray(4096) { index ->
        val value = index / 4095.0
        val srgb = if (value <= 0.0031308) {
            value * 12.92
        } else {
            1.055 * value.pow(1.0 / 2.4) - 0.055
        }
        (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    private const val TAG = "FilmExposureSimulator"
    private const val PARALLEL_PIXEL_THRESHOLD = 100_000
    private const val TARGET_LUMINANCE = 0.18
    private const val RED_LUMINANCE_WEIGHT = 0.2126
    private const val GREEN_LUMINANCE_WEIGHT = 0.7152
    private const val BLUE_LUMINANCE_WEIGHT = 0.0722
    private const val LUMINANCE_EPSILON = 1e-6
}
