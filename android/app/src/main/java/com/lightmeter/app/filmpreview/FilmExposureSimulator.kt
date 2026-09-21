package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.metering.ExposureMap
import java.util.stream.IntStream
import kotlin.math.exp
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
        deriveExposureFromSource: Boolean = false,
    ): Bitmap {
        require(referenceEv100.isFinite())
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
        require(exposureMap.cameraSettingEv100.isFinite())

        val startedAtMs = SystemClock.elapsedRealtime()
        val gpuResult = runCatching {
            GpuFilmExposureRenderer.render(
                source = source,
                exposureMap = exposureMap,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
                filmLook = filmLook,
                deriveExposureFromSource = deriveExposureFromSource,
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
                exposureMap = exposureMap.takeIf {
                    !deriveExposureFromSource &&
                    it.width == source.width && it.height == source.height
                },
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
        val sourceHeight = sourcePixels.size / sourceWidth
        val linearFilmPixels = FloatArray(sourcePixels.size * RGB_CHANNEL_COUNT)
        val deltaEvByPixel = FloatArray(sourcePixels.size)
        val renderFilmPixel: (Int) -> Unit = { index ->
            simulateLinearPixel(
                argb = sourcePixels[index],
                exposureMapEv100 = exposureMap?.pixelEv100?.get(index),
                cameraSettingEv100 = cameraSettingEv100,
                calibrationOffset = calibrationOffset,
                referenceEv100 = referenceEv100,
                filmLook = filmLook,
                preparedFilmLook = preparedFilmLook,
                output = linearFilmPixels,
                outputOffset = index * RGB_CHANNEL_COUNT,
                deltaEvByPixel = deltaEvByPixel,
                pixelIndex = index,
            )
        }
        forEachPixel(sourcePixels.size, renderFilmPixel)
        val spatialPixels = separableGaussian5Tap(
            source = linearFilmPixels,
            width = sourceWidth,
            height = sourceHeight,
            sigma = preparedFilmLook.lowPassSigma,
        )
        val outputPixels = IntArray(sourcePixels.size)
        val packPixel: (Int) -> Unit = { index ->
            outputPixels[index] = addGrainAndPack(
                alpha = sourcePixels[index] and -0x1000000,
                linearPixels = spatialPixels,
                linearOffset = index * RGB_CHANNEL_COUNT,
                deltaEv = deltaEvByPixel[index].toDouble(),
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
                filmLook = filmLook,
                preparedFilmLook = preparedFilmLook,
                x = index % sourceWidth,
                y = index / sourceWidth,
            )
        }
        forEachPixel(sourcePixels.size, packPixel)
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

    private fun simulateLinearPixel(
        argb: Int,
        exposureMapEv100: Float?,
        cameraSettingEv100: Double,
        calibrationOffset: Double,
        referenceEv100: Double,
        filmLook: NegativeFilmLookProfile,
        preparedFilmLook: PreparedFilmLook,
        output: FloatArray,
        outputOffset: Int,
        deltaEvByPixel: FloatArray,
        pixelIndex: Int,
    ) {
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
        val relativeExposure = TARGET_LUMINANCE * 2.0.pow(deltaEv)
        val gain = relativeExposure / sourceLuminance.coerceAtLeast(LUMINANCE_EPSILON)
        applyFilmLook(
            red = red * gain,
            green = green * gain,
            blue = blue * gain,
            filmLook = filmLook,
            preparedFilmLook = preparedFilmLook,
            output = output,
            outputOffset = outputOffset,
        )
        deltaEvByPixel[pixelIndex] = deltaEv.toFloat()
    }

    private fun applyFilmLook(
        red: Double,
        green: Double,
        blue: Double,
        filmLook: NegativeFilmLookProfile,
        preparedFilmLook: PreparedFilmLook,
        output: FloatArray,
        outputOffset: Int,
    ) {
        val matrix = filmLook.colorMatrix
        val layerRed = (
            matrix.redFromRed * red +
                matrix.redFromGreen * green +
                matrix.redFromBlue * blue
            ).coerceAtLeast(0.0)
        val layerGreen = (
            matrix.greenFromRed * red +
                matrix.greenFromGreen * green +
                matrix.greenFromBlue * blue
            ).coerceAtLeast(0.0)
        val layerBlue = (
            matrix.blueFromRed * red +
                matrix.blueFromGreen * green +
                matrix.blueFromBlue * blue
            ).coerceAtLeast(0.0)
        var styledRed = preparedFilmLook.responseLut.sampleRed(exposureEv(layerRed))
        var styledGreen = preparedFilmLook.responseLut.sampleGreen(exposureEv(layerGreen))
        var styledBlue = preparedFilmLook.responseLut.sampleBlue(exposureEv(layerBlue))

        val saturationCenter = luminance(styledRed, styledGreen, styledBlue)
        styledRed = saturationCenter + (styledRed - saturationCenter) * filmLook.saturation
        styledGreen = saturationCenter + (styledGreen - saturationCenter) * filmLook.saturation
        styledBlue = saturationCenter + (styledBlue - saturationCenter) * filmLook.saturation
        output[outputOffset] = styledRed.toFloat()
        output[outputOffset + 1] = styledGreen.toFloat()
        output[outputOffset + 2] = styledBlue.toFloat()
    }

    private fun addGrainAndPack(
        alpha: Int,
        linearPixels: FloatArray,
        linearOffset: Int,
        deltaEv: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        filmLook: NegativeFilmLookProfile,
        preparedFilmLook: PreparedFilmLook,
        x: Int,
        y: Int,
    ): Int {
        var styledRed = linearPixels[linearOffset].toDouble()
        var styledGreen = linearPixels[linearOffset + 1].toDouble()
        var styledBlue = linearPixels[linearOffset + 2].toDouble()
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

    private fun separableGaussian5Tap(
        source: FloatArray,
        width: Int,
        height: Int,
        sigma: Double,
    ): FloatArray {
        if (sigma < MIN_LOW_PASS_SIGMA_PX) return source
        val weights = gaussian5TapWeights(sigma)
        val horizontal = FloatArray(source.size)
        IntStream.range(0, height).parallel().forEach { y ->
            for (x in 0 until width) {
                val outputOffset = (y * width + x) * RGB_CHANNEL_COUNT
                for (channel in 0 until RGB_CHANNEL_COUNT) {
                    var value = 0.0
                    for (offset in -GAUSSIAN_RADIUS..GAUSSIAN_RADIUS) {
                        val sampleX = (x + offset).coerceIn(0, width - 1)
                        val sampleOffset = (y * width + sampleX) * RGB_CHANNEL_COUNT + channel
                        value += source[sampleOffset] * weights[offset + GAUSSIAN_RADIUS]
                    }
                    horizontal[outputOffset + channel] = value.toFloat()
                }
            }
        }
        IntStream.range(0, height).parallel().forEach { y ->
            for (x in 0 until width) {
                val outputOffset = (y * width + x) * RGB_CHANNEL_COUNT
                for (channel in 0 until RGB_CHANNEL_COUNT) {
                    var value = 0.0
                    for (offset in -GAUSSIAN_RADIUS..GAUSSIAN_RADIUS) {
                        val sampleY = (y + offset).coerceIn(0, height - 1)
                        val sampleOffset = (sampleY * width + x) * RGB_CHANNEL_COUNT + channel
                        value += horizontal[sampleOffset] * weights[offset + GAUSSIAN_RADIUS]
                    }
                    source[outputOffset + channel] = value.toFloat()
                }
            }
        }
        return source
    }

    private fun gaussian5TapWeights(sigma: Double): DoubleArray {
        val weights = DoubleArray(GAUSSIAN_KERNEL_SIZE) { index ->
            val offset = index - GAUSSIAN_RADIUS
            exp(-(offset * offset) / (2.0 * sigma * sigma))
        }
        val sum = weights.sum()
        return DoubleArray(weights.size) { index -> weights[index] / sum }
    }

    private fun forEachPixel(pixelCount: Int, action: (Int) -> Unit) {
        if (pixelCount >= PARALLEL_PIXEL_THRESHOLD) {
            IntStream.range(0, pixelCount).parallel().forEach(action)
        } else {
            repeat(pixelCount, action)
        }
    }

    private fun exposureEv(layerExposure: Double): Double {
        return log2(layerExposure.coerceAtLeast(LUMINANCE_EPSILON) / TARGET_LUMINANCE)
    }

    private fun prepareFilmLook(
        filmLook: NegativeFilmLookProfile,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        sourceWidth: Int,
        frameTimestampNs: Long,
    ): PreparedFilmLook {
        return PreparedFilmLook(
            responseLut = FilmResponseLut.create(
                filmLook = filmLook,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
            ),
            grainRadius = (
                filmLook.grainRadiusPxAt1080 * sourceWidth / GRAIN_REFERENCE_WIDTH
                ).coerceAtLeast(MIN_GRAIN_RADIUS_PX),
            lowPassSigma = filmLook.lowPassSigmaPxAt1080 *
                sourceWidth / GRAIN_REFERENCE_WIDTH,
            grainSeed = filmLook.grainSeedForFrame(frameTimestampNs),
        )
    }

    private fun fractalNoise(
        x: Int,
        y: Int,
        radius: Double,
        seed: Int,
    ): Double {
        val micro = valueNoise(
            x / (radius * GRAIN_MICRO_SCALE),
            y / (radius * GRAIN_MICRO_SCALE),
            seed,
        )
        val fine = valueNoise(x / radius, y / radius, seed + 97)
        val coarse = valueNoise(x / (radius * 2.0), y / (radius * 2.0), seed + 193)
        return GRAIN_MICRO_WEIGHT * micro +
            GRAIN_FINE_WEIGHT * fine +
            GRAIN_COARSE_WEIGHT * coarse
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
        val responseLut: FilmResponseLut,
        val grainRadius: Double,
        val lowPassSigma: Double,
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
    private const val RGB_CHANNEL_COUNT = 3
    private const val GAUSSIAN_RADIUS = 2
    private const val GAUSSIAN_KERNEL_SIZE = GAUSSIAN_RADIUS * 2 + 1
    private const val MIN_LOW_PASS_SIGMA_PX = 0.05
    private const val GRAIN_REFERENCE_WIDTH = 1080.0
    private const val MIN_GRAIN_RADIUS_PX = 0.65
    private const val GRAIN_EV_SCALE = 0.22
    private const val GRAIN_FLOOR = 0.20
    private const val GRAIN_SHADOW_BIAS = 0.10
    private const val GRAIN_MICRO_SCALE = 0.55
    private const val GRAIN_MICRO_WEIGHT = 0.55
    private const val GRAIN_FINE_WEIGHT = 0.30
    private const val GRAIN_COARSE_WEIGHT = 0.15
    private const val RED_GRAIN_SEED_OFFSET = 17
    private const val GREEN_GRAIN_SEED_OFFSET = 37
    private const val BLUE_GRAIN_SEED_OFFSET = 67
}
