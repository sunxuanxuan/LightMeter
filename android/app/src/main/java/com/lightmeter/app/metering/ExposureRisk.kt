package com.lightmeter.app.metering

import kotlin.math.abs
import kotlin.math.roundToInt

data class ExposureMap(
    val width: Int,
    val height: Int,
    val pixelEv100: FloatArray,
    val rawLuminance: ByteArray? = null,
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
        require(rawLuminance == null || rawLuminance.size == pixelEv100.size)
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

data class ExposureProbeRequirements(
    val highlight: Boolean,
    val shadow: Boolean,
) {
    val isEmpty: Boolean get() = !highlight && !shadow
}

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
        shadowProbeMap: ExposureMap? = null,
        highlightProbeMap: ExposureMap? = null,
        warningStartStops: Double? = null,
    ): ExposureRiskMask {
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
        warningStartStops?.let {
            require(it > 0.0)
        }
        val compatibleShadowProbe = compatibleProbe(exposureMap, shadowProbeMap)
        val compatibleHighlightProbe = compatibleProbe(exposureMap, highlightProbeMap)
        val baselineDetails = if (
            compatibleShadowProbe != null || compatibleHighlightProbe != null
        ) {
            LocalDetailStats(exposureMap)
        } else {
            null
        }
        val shadowProbeDetails = compatibleShadowProbe?.let(::LocalDetailStats)
        val highlightProbeDetails = compatibleHighlightProbe?.let(::LocalDetailStats)

        val pixels = IntArray(exposureMap.pixelEv100.size)
        var highlightCount = 0
        var shadowCount = 0
        var analyzedCount = 0

        exposureMap.pixelEv100.forEachIndexed { index, pixelEv ->
            val x = (index % exposureMap.width + 0.5) / exposureMap.width
            val y = (index / exposureMap.width + 0.5) / exposureMap.height
            if (!viewfinder.contains(x, y) || !pixelEv.isFinite()) {
                pixels[index] = TRANSPARENT
                return@forEachIndexed
            }

            analyzedCount++
            val deltaEv = pixelEv - referenceEv100
            if (warningStartStops != null) {
                val isHighlightDetailLoss =
                    exposureMap.clippedHighlights[index] ||
                        deltaEv >= highlightLatitudeStops
                val isShadowDetailLoss = deltaEv <= -shadowLatitudeStops
                val highlightConfirmed =
                    !isHighlightDetailLoss ||
                        highlightProbeDetails == null ||
                        DetailRevealDetector.isHighlightDetailRevealed(
                            baseline = requireNotNull(baselineDetails),
                            probe = highlightProbeDetails,
                            index = index,
                        )
                val shadowConfirmed =
                    !isShadowDetailLoss ||
                        shadowProbeDetails == null ||
                        DetailRevealDetector.isShadowDetailRevealed(
                            baseline = requireNotNull(baselineDetails),
                            probe = shadowProbeDetails,
                            index = index,
                        )
                pixels[index] = when {
                    exposureMap.clippedHighlights[index] && highlightConfirmed -> {
                        highlightCount++
                        riskColor(HIGHLIGHT_RGB, MAX_RISK_ALPHA)
                    }

                    deltaEv > warningStartStops && highlightConfirmed -> {
                        highlightCount++
                        progressiveWarningColor(
                            rgb = HIGHLIGHT_RGB,
                            distanceStops = deltaEv,
                            warningStartStops = warningStartStops,
                            detailLossStops = highlightLatitudeStops,
                        )
                    }

                    deltaEv < -warningStartStops && shadowConfirmed -> {
                        shadowCount++
                        progressiveWarningColor(
                            rgb = SHADOW_RGB,
                            distanceStops = -deltaEv,
                            warningStartStops = warningStartStops,
                            detailLossStops = shadowLatitudeStops,
                        )
                    }

                    else -> TRANSPARENT
                }
                return@forEachIndexed
            }

            val isHighlightCandidate = exposureMap.clippedHighlights[index] ||
                deltaEv >= highlightLatitudeStops
            pixels[index] = when {
                isHighlightCandidate -> {
                    if (
                        highlightProbeDetails == null ||
                        DetailRevealDetector.isHighlightDetailRevealed(
                            baseline = requireNotNull(baselineDetails),
                            probe = highlightProbeDetails,
                            index = index,
                        )
                    ) {
                        highlightCount++
                        riskColor(
                            rgb = HIGHLIGHT_RGB,
                            excessStops = if (exposureMap.clippedHighlights[index]) {
                                FULL_INTENSITY_EXCESS_STOPS
                            } else {
                                deltaEv - highlightLatitudeStops
                            },
                        )
                    } else {
                        TRANSPARENT
                    }
                }

                deltaEv <= -shadowLatitudeStops &&
                    (
                        compatibleShadowProbe == null ||
                            DetailRevealDetector.isShadowDetailRevealed(
                                baseline = requireNotNull(baselineDetails),
                                probe = requireNotNull(shadowProbeDetails),
                                index = index,
                            )
                    ) -> {
                    shadowCount++
                    riskColor(
                        rgb = SHADOW_RGB,
                        excessStops = -deltaEv - shadowLatitudeStops,
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

    fun probeRequirements(
        exposureMap: ExposureMap,
        viewfinder: NormalizedMeteringRect,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): ExposureProbeRequirements {
        var needsHighlightProbe = false
        var needsShadowProbe = false
        exposureMap.pixelEv100.forEachIndexed { index, pixelEv ->
            if (!pixelEv.isFinite()) return@forEachIndexed
            val x = (index % exposureMap.width + 0.5) / exposureMap.width
            val y = (index / exposureMap.width + 0.5) / exposureMap.height
            if (!viewfinder.contains(x, y)) return@forEachIndexed

            val deltaEv = pixelEv - referenceEv100
            needsHighlightProbe = needsHighlightProbe ||
                exposureMap.clippedHighlights[index] ||
                deltaEv >= highlightLatitudeStops
            needsShadowProbe = needsShadowProbe || deltaEv <= -shadowLatitudeStops
            if (needsHighlightProbe && needsShadowProbe) {
                return ExposureProbeRequirements(highlight = true, shadow = true)
            }
        }
        return ExposureProbeRequirements(
            highlight = needsHighlightProbe,
            shadow = needsShadowProbe,
        )
    }

    private fun compatibleProbe(
        baseline: ExposureMap,
        probe: ExposureMap?,
    ): ExposureMap? {
        return probe?.takeIf {
            baseline.rawLuminance != null &&
                it.rawLuminance != null &&
                it.width == baseline.width &&
                it.height == baseline.height
        }
    }

    private fun riskColor(rgb: Int, excessStops: Double): Int {
        val intensity = (excessStops / FULL_INTENSITY_EXCESS_STOPS).coerceIn(0.0, 1.0)
        val alpha = (
            MIN_RISK_ALPHA +
                (MAX_RISK_ALPHA - MIN_RISK_ALPHA) * intensity
            ).roundToInt()
        return (alpha shl 24) or rgb
    }

    private fun progressiveWarningColor(
        rgb: Int,
        distanceStops: Double,
        warningStartStops: Double,
        detailLossStops: Double,
    ): Int {
        if (detailLossStops <= warningStartStops) {
            return riskColor(rgb, MAX_RISK_ALPHA)
        }
        val intensity = (
            (distanceStops - warningStartStops) /
                (detailLossStops - warningStartStops)
            ).coerceIn(0.0, 1.0)
        val alpha = (
            MIN_WARNING_ALPHA +
                (MAX_RISK_ALPHA - MIN_WARNING_ALPHA) * intensity
            ).roundToInt()
        return (alpha shl 24) or rgb
    }

    private fun riskColor(rgb: Int, alpha: Int): Int {
        return (alpha shl 24) or rgb
    }

    private const val MIN_WARNING_ALPHA = 0x20
}

private object DetailRevealDetector {
    private const val MIN_BRIGHTNESS_CHANGE = 12.0
    private const val MAX_BASELINE_GRADIENT = 4.0
    private const val MIN_PROBE_GRADIENT = 7.0
    private const val MIN_GRADIENT_GAIN = 3.0
    private const val MIN_PROBE_MEAN = 10.0
    private const val MAX_PROBE_MEAN = 245.0

    fun isShadowDetailRevealed(
        baseline: LocalDetailStats,
        probe: LocalDetailStats,
        index: Int,
    ): Boolean {
        val probeMean = probe.mean(index)
        return probeMean - baseline.mean(index) >= MIN_BRIGHTNESS_CHANGE &&
            hasNewDetail(baseline, probe, index) &&
            probeMean <= MAX_PROBE_MEAN
    }

    fun isHighlightDetailRevealed(
        baseline: LocalDetailStats,
        probe: LocalDetailStats,
        index: Int,
    ): Boolean {
        val probeMean = probe.mean(index)
        return baseline.mean(index) - probeMean >= MIN_BRIGHTNESS_CHANGE &&
            hasNewDetail(baseline, probe, index) &&
            probeMean >= MIN_PROBE_MEAN
    }

    private fun hasNewDetail(
        baseline: LocalDetailStats,
        probe: LocalDetailStats,
        index: Int,
    ): Boolean {
        val baselineGradient = baseline.gradient(index)
        val probeGradient = probe.gradient(index)
        return baselineGradient <= MAX_BASELINE_GRADIENT &&
            probeGradient >= MIN_PROBE_GRADIENT &&
            probeGradient - baselineGradient >= MIN_GRADIENT_GAIN
    }
}

private class LocalDetailStats(map: ExposureMap) {
    private val width = map.width
    private val height = map.height
    private val integralStride = width + 1
    private val luminanceSum = DoubleArray((width + 1) * (height + 1))
    private val luminanceCount = IntArray(luminanceSum.size)
    private val gradientSum = DoubleArray(luminanceSum.size)
    private val gradientCount = IntArray(luminanceSum.size)

    init {
        val rawLuminance = requireNotNull(map.rawLuminance)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val isValid = map.pixelEv100[index].isFinite()
                val value = rawLuminance[index].toInt() and 0xFF
                var localGradientSum = 0.0
                var localGradientCount = 0
                if (isValid && x + 1 < width && map.pixelEv100[index + 1].isFinite()) {
                    localGradientSum += abs(
                        value - (rawLuminance[index + 1].toInt() and 0xFF),
                    )
                    localGradientCount++
                }
                if (isValid && y + 1 < height && map.pixelEv100[index + width].isFinite()) {
                    localGradientSum += abs(
                        value - (rawLuminance[index + width].toInt() and 0xFF),
                    )
                    localGradientCount++
                }

                val integralIndex = (y + 1) * integralStride + x + 1
                val above = integralIndex - integralStride
                val left = integralIndex - 1
                val aboveLeft = above - 1
                luminanceSum[integralIndex] = if (isValid) value.toDouble() else 0.0
                luminanceCount[integralIndex] = if (isValid) 1 else 0
                gradientSum[integralIndex] = localGradientSum
                gradientCount[integralIndex] = localGradientCount
                luminanceSum[integralIndex] +=
                    luminanceSum[above] + luminanceSum[left] - luminanceSum[aboveLeft]
                luminanceCount[integralIndex] +=
                    luminanceCount[above] + luminanceCount[left] - luminanceCount[aboveLeft]
                gradientSum[integralIndex] +=
                    gradientSum[above] + gradientSum[left] - gradientSum[aboveLeft]
                gradientCount[integralIndex] +=
                    gradientCount[above] + gradientCount[left] - gradientCount[aboveLeft]
            }
        }
    }

    fun mean(index: Int): Double {
        val bounds = bounds(index)
        val count = areaSum(luminanceCount, bounds).coerceAtLeast(1)
        return areaSum(luminanceSum, bounds) / count
    }

    fun gradient(index: Int): Double {
        val bounds = bounds(index)
        val count = areaSum(gradientCount, bounds).coerceAtLeast(1)
        return areaSum(gradientSum, bounds) / count
    }

    private fun bounds(index: Int): Bounds {
        val centerX = index % width
        val centerY = index / width
        return Bounds(
            left = (centerX - WINDOW_RADIUS).coerceAtLeast(0),
            top = (centerY - WINDOW_RADIUS).coerceAtLeast(0),
            rightExclusive = (centerX + WINDOW_RADIUS + 1).coerceAtMost(width),
            bottomExclusive = (centerY + WINDOW_RADIUS + 1).coerceAtMost(height),
        )
    }

    private fun areaSum(integral: DoubleArray, bounds: Bounds): Double {
        val topLeft = bounds.top * integralStride + bounds.left
        val topRight = bounds.top * integralStride + bounds.rightExclusive
        val bottomLeft = bounds.bottomExclusive * integralStride + bounds.left
        val bottomRight =
            bounds.bottomExclusive * integralStride + bounds.rightExclusive
        return integral[bottomRight] - integral[topRight] -
            integral[bottomLeft] + integral[topLeft]
    }

    private fun areaSum(integral: IntArray, bounds: Bounds): Int {
        val topLeft = bounds.top * integralStride + bounds.left
        val topRight = bounds.top * integralStride + bounds.rightExclusive
        val bottomLeft = bounds.bottomExclusive * integralStride + bounds.left
        val bottomRight =
            bounds.bottomExclusive * integralStride + bounds.rightExclusive
        return integral[bottomRight] - integral[topRight] -
            integral[bottomLeft] + integral[topLeft]
    }

    private data class Bounds(
        val left: Int,
        val top: Int,
        val rightExclusive: Int,
        val bottomExclusive: Int,
    )

    private companion object {
        const val WINDOW_RADIUS = 4
    }
}
