package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.metering.ExposureMap
import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

internal object FilmExposureSimulator {
    fun renderExposureCompensation(
        source: Bitmap,
        exposureCompensation: Double,
    ): Bitmap {
        require(exposureCompensation.isFinite())
        if (exposureCompensation == 0.0) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        val gpuResult = runCatching {
            GpuFilmExposureRenderer.renderExposureCompensation(
                source = source,
                exposureCompensation = exposureCompensation,
            )
        }
        gpuResult.getOrNull()?.let { result ->
            logRender("gpu-ec", source, result, startedAtMs)
            return result
        }
        if (BuildConfig.DEBUG) {
            Log.w(
                TAG,
                "GPU exposure compensation failed; using CPU",
                gpuResult.exceptionOrNull(),
            )
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
            renderExposureCompensationPixels(sourcePixels, exposureCompensation),
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
        logRender("cpu-ec", source, result, startedAtMs)
        return result
    }

    fun render(
        source: Bitmap,
        exposureMap: ExposureMap,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        filmLook: NegativeFilmLookProfile = NegativeFilmLooks.NEUTRAL,
    ): Bitmap {
        require(referenceEv100.isFinite())
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
        require(exposureMap.cameraSettingEv100.isFinite())
        require(exposureMap.width == source.width)
        require(exposureMap.height == source.height)

        val startedAtMs = SystemClock.elapsedRealtime()
        val gpuResult = runCatching {
            GpuFilmExposureRenderer.render(
                source = source,
                exposureMap = exposureMap,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
                filmLook = filmLook,
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
                exposureMap = exposureMap,
                cameraSettingEv100 = exposureMap.cameraSettingEv100,
                calibrationOffset = exposureMap.calibrationOffset,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
                sourceWidth = source.width,
                filmLook = filmLook,
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
        exposureMap: ExposureMap? = null,
        cameraSettingEv100: Double,
        calibrationOffset: Double,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        sourceWidth: Int = sourcePixels.size,
        filmLook: NegativeFilmLookProfile = NegativeFilmLooks.NEUTRAL,
    ): IntArray {
        require(cameraSettingEv100.isFinite())
        require(calibrationOffset.isFinite())
        require(referenceEv100.isFinite())
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
        require(sourceWidth > 0)
        require(sourcePixels.size % sourceWidth == 0)
        exposureMap?.let {
            require(it.width == sourceWidth)
            require(it.height == sourcePixels.size / sourceWidth)
        }

        val preparedFilmLook = prepareFilmLook(
            filmLook = filmLook,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
            sourceWidth = sourceWidth,
            frameTimestampNs = exposureMap?.timestampNs ?: 0L,
        )
        val outputPixels = IntArray(sourcePixels.size)
        val renderPixel: (Int) -> Unit = { index ->
            outputPixels[index] = simulatePixel(
                argb = sourcePixels[index],
                exposureMapEv100 = exposureMap?.pixelEv100?.get(index),
                cameraSettingEv100 = cameraSettingEv100,
                calibrationOffset = calibrationOffset,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
                filmLook = filmLook,
                preparedFilmLook = preparedFilmLook,
                x = index % sourceWidth,
                y = index / sourceWidth,
            )
        }
        if (sourcePixels.size >= PARALLEL_PIXEL_THRESHOLD) {
            IntStream.range(0, sourcePixels.size).parallel().forEach(renderPixel)
        } else {
            sourcePixels.indices.forEach(renderPixel)
        }
        return outputPixels
    }

    internal fun renderExposureCompensationPixels(
        sourcePixels: IntArray,
        exposureCompensation: Double,
    ): IntArray {
        require(exposureCompensation.isFinite())
        if (exposureCompensation == 0.0) return sourcePixels.copyOf()

        val gain = 2.0.pow(exposureCompensation)
        val outputPixels = IntArray(sourcePixels.size)
        val renderPixel: (Int) -> Unit = { index ->
            val argb = sourcePixels[index]
            val red = SRGB_TO_LINEAR[(argb ushr 16) and 0xFF]
            val green = SRGB_TO_LINEAR[(argb ushr 8) and 0xFF]
            val blue = SRGB_TO_LINEAR[argb and 0xFF]
            outputPixels[index] = (argb and -0x1000000) or
                (linearToByte(red * gain) shl 16) or
                (linearToByte(green * gain) shl 8) or
                linearToByte(blue * gain)
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
        exposureMapEv100: Float?,
        cameraSettingEv100: Double,
        calibrationOffset: Double,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        filmLook: NegativeFilmLookProfile,
        preparedFilmLook: PreparedFilmLook,
        x: Int,
        y: Int,
    ): Int {
        val red = SRGB_TO_LINEAR[(argb ushr 16) and 0xFF]
        val green = SRGB_TO_LINEAR[(argb ushr 8) and 0xFF]
        val blue = SRGB_TO_LINEAR[argb and 0xFF]
        val sourceLuminance = RED_LUMINANCE_WEIGHT * red +
            GREEN_LUMINANCE_WEIGHT * green +
            BLUE_LUMINANCE_WEIGHT * blue
        val pixelEv100 = exposureMapEv100
            ?.takeIf(Float::isFinite)
            ?.toDouble()
            ?: (
                cameraSettingEv100 +
                    log2(sourceLuminance.coerceAtLeast(LUMINANCE_EPSILON) / TARGET_LUMINANCE) +
                    calibrationOffset
                )
        val deltaEv = pixelEv100 - referenceEv100
        val toneDeltaEv = deltaEv * filmLook.toneGamma
        val targetLuminance = FilmResponseCurve.targetLuminance(
            deltaEv = toneDeltaEv,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
        )
        val gain = targetLuminance / sourceLuminance.coerceAtLeast(LUMINANCE_EPSILON)
        return applyFilmLookAndPack(
            alpha = argb and -0x1000000,
            red = red * gain,
            green = green * gain,
            blue = blue * gain,
            targetLuminance = targetLuminance,
            deltaEv = deltaEv,
            toneDeltaEv = toneDeltaEv,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
            filmLook = filmLook,
            preparedFilmLook = preparedFilmLook,
            x = x,
            y = y,
        )
    }

    private fun applyFilmLookAndPack(
        alpha: Int,
        red: Double,
        green: Double,
        blue: Double,
        targetLuminance: Double,
        deltaEv: Double,
        toneDeltaEv: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        filmLook: NegativeFilmLookProfile,
        preparedFilmLook: PreparedFilmLook,
        x: Int,
        y: Int,
    ): Int {
        val redFactor = channelResponseFactor(
            deltaEv = toneDeltaEv,
            gamma = filmLook.responseGamma.red,
            meanGamma = preparedFilmLook.meanGamma,
            biasEv = filmLook.exposureBiasEv.red,
            grayAnchor = preparedFilmLook.redGrayAnchor,
            targetLuminance = targetLuminance,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
        )
        val greenFactor = channelResponseFactor(
            deltaEv = toneDeltaEv,
            gamma = filmLook.responseGamma.green,
            meanGamma = preparedFilmLook.meanGamma,
            biasEv = filmLook.exposureBiasEv.green,
            grayAnchor = preparedFilmLook.greenGrayAnchor,
            targetLuminance = targetLuminance,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
        )
        val blueFactor = channelResponseFactor(
            deltaEv = toneDeltaEv,
            gamma = filmLook.responseGamma.blue,
            meanGamma = preparedFilmLook.meanGamma,
            biasEv = filmLook.exposureBiasEv.blue,
            grayAnchor = preparedFilmLook.blueGrayAnchor,
            targetLuminance = targetLuminance,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
        )
        val responseRed = red * redFactor
        val responseGreen = green * greenFactor
        val responseBlue = blue * blueFactor
        val matrix = filmLook.colorMatrix
        var styledRed = (
            matrix.redFromRed * responseRed +
                matrix.redFromGreen * responseGreen +
                matrix.redFromBlue * responseBlue
            ).coerceAtLeast(0.0)
        var styledGreen = (
            matrix.greenFromRed * responseRed +
                matrix.greenFromGreen * responseGreen +
                matrix.greenFromBlue * responseBlue
            ).coerceAtLeast(0.0)
        var styledBlue = (
            matrix.blueFromRed * responseRed +
                matrix.blueFromGreen * responseGreen +
                matrix.blueFromBlue * responseBlue
            ).coerceAtLeast(0.0)
        val responseLuminance = luminance(styledRed, styledGreen, styledBlue)
        if (responseLuminance > LUMINANCE_EPSILON) {
            val responseGain = targetLuminance / responseLuminance
            styledRed *= responseGain
            styledGreen *= responseGain
            styledBlue *= responseGain
        }

        val saturationCenter = luminance(styledRed, styledGreen, styledBlue)
        styledRed = saturationCenter + (styledRed - saturationCenter) * filmLook.saturation
        styledGreen = saturationCenter + (styledGreen - saturationCenter) * filmLook.saturation
        styledBlue = saturationCenter + (styledBlue - saturationCenter) * filmLook.saturation

        if (filmLook.grainAmount > 0.0) {
            val densityPosition = (
                (deltaEv + shadowLatitudeStops) /
                    (shadowLatitudeStops + highlightLatitudeStops) +
                    GRAIN_SHADOW_BIAS
                ).coerceIn(0.0, 1.0)
            val densityEnvelope = GRAIN_FLOOR +
                4.0 * densityPosition * (1.0 - densityPosition)
            val radius = preparedFilmLook.grainRadius
            val sharedNoise = fractalNoise(x, y, radius, preparedFilmLook.grainSeed)
            val chroma = filmLook.grainChromaFraction
            val amplitude = GRAIN_EV_SCALE * filmLook.grainAmount * densityEnvelope
            styledRed *= 2.0.pow(
                amplitude * mixNoise(
                    sharedNoise,
                    fractalNoise(
                        x,
                        y,
                        radius,
                        preparedFilmLook.grainSeed + RED_GRAIN_SEED_OFFSET,
                    ),
                    chroma,
                ),
            )
            styledGreen *= 2.0.pow(
                amplitude * mixNoise(
                    sharedNoise,
                    fractalNoise(
                        x,
                        y,
                        radius,
                        preparedFilmLook.grainSeed + GREEN_GRAIN_SEED_OFFSET,
                    ),
                    chroma,
                ),
            )
            styledBlue *= 2.0.pow(
                amplitude * mixNoise(
                    sharedNoise,
                    fractalNoise(
                        x,
                        y,
                        radius,
                        preparedFilmLook.grainSeed + BLUE_GRAIN_SEED_OFFSET,
                    ),
                    chroma,
                ),
            )
        }

        return alpha or
            (linearToByte(styledRed.coerceAtLeast(0.0)) shl 16) or
            (linearToByte(styledGreen.coerceAtLeast(0.0)) shl 8) or
            linearToByte(styledBlue.coerceAtLeast(0.0))
    }

    private fun channelResponseFactor(
        deltaEv: Double,
        gamma: Double,
        meanGamma: Double,
        biasEv: Double,
        grayAnchor: Double,
        targetLuminance: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): Double {
        if (targetLuminance <= LUMINANCE_EPSILON) return 1.0
        val response = FilmResponseCurve.targetLuminance(
            deltaEv = deltaEv * gamma / meanGamma + biasEv,
            highlightLatitudeStops = highlightLatitudeStops,
            shadowLatitudeStops = shadowLatitudeStops,
        )
        val normalizedResponse = response * TARGET_LUMINANCE /
            grayAnchor.coerceAtLeast(LUMINANCE_EPSILON)
        return normalizedResponse / targetLuminance
    }

    private fun prepareFilmLook(
        filmLook: NegativeFilmLookProfile,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        sourceWidth: Int,
        frameTimestampNs: Long,
    ): PreparedFilmLook {
        fun grayAnchor(biasEv: Double): Double {
            return FilmResponseCurve.targetLuminance(
                deltaEv = biasEv,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
            )
        }
        return PreparedFilmLook(
            meanGamma = filmLook.responseGamma.average(),
            redGrayAnchor = grayAnchor(filmLook.exposureBiasEv.red),
            greenGrayAnchor = grayAnchor(filmLook.exposureBiasEv.green),
            blueGrayAnchor = grayAnchor(filmLook.exposureBiasEv.blue),
            grainRadius = (
                filmLook.grainRadiusPxAt1080 * sourceWidth / GRAIN_REFERENCE_WIDTH
                ).coerceAtLeast(MIN_GRAIN_RADIUS_PX),
            grainSeed = filmLook.grainSeedForFrame(frameTimestampNs),
        )
    }

    private fun fractalNoise(
        x: Int,
        y: Int,
        radius: Double,
        seed: Int,
    ): Double {
        val fine = valueNoise(x / radius, y / radius, seed)
        val coarse = valueNoise(x / (radius * 2.0), y / (radius * 2.0), seed + 97)
        return (fine + 0.5 * coarse) / 1.5
    }

    private fun valueNoise(x: Double, y: Double, seed: Int): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = smoothstep(x - x0)
        val ty = smoothstep(y - y0)
        val top = lerp(
            hashNoise(x0, y0, seed),
            hashNoise(x0 + 1, y0, seed),
            tx,
        )
        val bottom = lerp(
            hashNoise(x0, y0 + 1, seed),
            hashNoise(x0 + 1, y0 + 1, seed),
            tx,
        )
        return lerp(top, bottom, ty)
    }

    private fun hashNoise(x: Int, y: Int, seed: Int): Double {
        var value = x * 374_761_393 + y * 668_265_263 + seed * 1_274_126_177
        value = (value xor (value ushr 13)) * 1_274_126_177
        value = value xor (value ushr 16)
        return (value and Int.MAX_VALUE) / Int.MAX_VALUE.toDouble() * 2.0 - 1.0
    }

    private fun smoothstep(value: Double): Double {
        val normalized = value.coerceIn(0.0, 1.0)
        return normalized * normalized * (3.0 - 2.0 * normalized)
    }

    private fun lerp(from: Double, to: Double, progress: Double): Double {
        return from + (to - from) * progress
    }

    private fun mixNoise(shared: Double, independent: Double, chroma: Double): Double {
        return shared * (1.0 - chroma) + independent * chroma
    }

    private fun luminance(red: Double, green: Double, blue: Double): Double {
        return RED_LUMINANCE_WEIGHT * red +
            GREEN_LUMINANCE_WEIGHT * green +
            BLUE_LUMINANCE_WEIGHT * blue
    }

    private data class PreparedFilmLook(
        val meanGamma: Double,
        val redGrayAnchor: Double,
        val greenGrayAnchor: Double,
        val blueGrayAnchor: Double,
        val grainRadius: Double,
        val grainSeed: Int,
    )

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
    private const val GRAIN_REFERENCE_WIDTH = 1080.0
    private const val MIN_GRAIN_RADIUS_PX = 0.65
    private const val GRAIN_EV_SCALE = 0.16
    private const val GRAIN_FLOOR = 0.20
    private const val GRAIN_SHADOW_BIAS = 0.10
    private const val RED_GRAIN_SEED_OFFSET = 17
    private const val GREEN_GRAIN_SEED_OFFSET = 37
    private const val BLUE_GRAIN_SEED_OFFSET = 67
}
