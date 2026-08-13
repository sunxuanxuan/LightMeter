package com.lightmeter.app.metering

import kotlin.math.roundToInt

data class ExposureMap(
    val width: Int,
    val height: Int,
    val pixelEv100: FloatArray,
    val clippedHighlights: BooleanArray = BooleanArray(pixelEv100.size),
    val cameraSettingEv100: Double = Double.NaN,
    val calibrationOffset: Double = 0.0,
    val timestampNs: Long,
    val revision: Long = 0L,
) {
    init {
        require(width > 0)
        require(height > 0)
        require(pixelEv100.size == width * height)
        require(clippedHighlights.size == pixelEv100.size)
        require(calibrationOffset.isFinite())
    }
}

data class ExposureRiskMask(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val highlightRatio: Double,
    val shadowRatio: Double,
)

object ExposureRiskCalculator {
    private const val HIGHLIGHT_RGB = 0x00FF2D2D
    private const val SHADOW_RGB = 0x0000D26A
    private const val TRANSPARENT = 0x00000000
    private const val MIN_RISK_ALPHA = 0x33
    private const val MAX_RISK_ALPHA = 0xE6
    private const val FULL_INTENSITY_EXCESS_STOPS = 2.0

    fun referenceEv100(
        frozenMeteredEv100: Double,
        exposureCompensation: Double,
    ): Double {
        require(frozenMeteredEv100.isFinite())
        require(exposureCompensation.isFinite())
        return frozenMeteredEv100 - exposureCompensation
    }

    fun calculate(
        exposureMap: ExposureMap,
        viewfinder: NormalizedMeteringRect,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): ExposureRiskMask {
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)

        val pixels = IntArray(exposureMap.pixelEv100.size)
        var highlightCount = 0
        var shadowCount = 0
        var analyzedCount = 0
        val highlightThreshold = quantizeMagnitudeToTenth(highlightLatitudeStops)
        val shadowThreshold = quantizeMagnitudeToTenth(shadowLatitudeStops)

        exposureMap.pixelEv100.forEachIndexed { index, pixelEv ->
            val x = (index % exposureMap.width + 0.5) / exposureMap.width
            val y = (index / exposureMap.width + 0.5) / exposureMap.height
            if (!viewfinder.contains(x, y) || !pixelEv.isFinite()) {
                pixels[index] = TRANSPARENT
                return@forEachIndexed
            }

            analyzedCount++
            val deltaEv = pixelEv - referenceEv100
            val deltaMagnitude = quantizeMagnitudeToTenth(deltaEv)
            val isHighlightCandidate = exposureMap.clippedHighlights[index] ||
                deltaEv > 0.0 && deltaMagnitude > highlightThreshold
            pixels[index] = when {
                isHighlightCandidate -> {
                    highlightCount++
                    riskColor(
                        rgb = HIGHLIGHT_RGB,
                        excessStops = if (exposureMap.clippedHighlights[index]) {
                            FULL_INTENSITY_EXCESS_STOPS
                        } else {
                            deltaMagnitude - highlightThreshold
                        },
                    )
                }

                deltaEv < 0.0 && deltaMagnitude > shadowThreshold -> {
                    shadowCount++
                    riskColor(
                        rgb = SHADOW_RGB,
                        excessStops = deltaMagnitude - shadowThreshold,
                    )
                }

                else -> TRANSPARENT
            }
        }

        return ExposureRiskMask(
            width = exposureMap.width,
            height = exposureMap.height,
            argb = pixels,
            highlightRatio = highlightCount / analyzedCount.coerceAtLeast(1).toDouble(),
            shadowRatio = shadowCount / analyzedCount.coerceAtLeast(1).toDouble(),
        )
    }

    private fun riskColor(rgb: Int, excessStops: Double): Int {
        val intensity = (excessStops / FULL_INTENSITY_EXCESS_STOPS).coerceIn(0.0, 1.0)
        val alpha = (
            MIN_RISK_ALPHA +
                (MAX_RISK_ALPHA - MIN_RISK_ALPHA) * intensity
            ).roundToInt()
        return (alpha shl 24) or rgb
    }

    private fun quantizeMagnitudeToTenth(stops: Double): Double =
        (kotlin.math.abs(stops) * EV_COMPARISON_STEPS_PER_STOP).roundToInt() /
            EV_COMPARISON_STEPS_PER_STOP

    private fun riskColor(rgb: Int, alpha: Int): Int {
        return (alpha shl 24) or rgb
    }

    private const val EV_COMPARISON_STEPS_PER_STOP = 10.0
}
