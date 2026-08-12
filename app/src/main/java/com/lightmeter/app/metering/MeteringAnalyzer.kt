package com.lightmeter.app.metering

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lightmeter.app.camera.CameraExposureMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class MeteringAnalyzer(
    initialConfig: MeteringConfig = MeteringConfig(),
    private val onResult: (MeteringResult) -> Unit,
) : CameraCaptureSession.CaptureCallback(), ImageAnalysis.Analyzer {
    private val config = AtomicReference(initialConfig)
    private val fallbackAperture = AtomicReference<Double?>(null)
    private val metadataByTimestamp = ConcurrentHashMap<Long, CameraExposureMetadata>()

    @Volatile
    private var latestMetadata: CameraExposureMetadata? = null
    private var lastAnalyzedTimestampNs = 0L
    private var smoothedEv: Double? = null
    private var previousConfig = initialConfig

    fun updateConfig(newConfig: MeteringConfig) {
        config.set(newConfig)
    }

    fun updateFallbackAperture(aperture: Double?) {
        fallbackAperture.set(aperture?.takeIf { it > 0.0 })
    }

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?.takeIf { it > 0L }
            ?: return
        val sensorSensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY)
            ?.takeIf { it > 0 }
            ?: return
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?.toDouble()
            ?.takeIf { it > 0.0 }
            ?: fallbackAperture.get()
            ?: return

        val metadata = CameraExposureMetadata(
            exposureTimeNs = exposureTimeNs,
            sensorSensitivity = sensorSensitivity,
            aperture = aperture,
            timestampNs = timestamp,
        )
        metadataByTimestamp[timestamp] = metadata
        latestMetadata = metadata

        if (metadataByTimestamp.size > MAX_METADATA_ENTRIES) {
            metadataByTimestamp.keys
                .sorted()
                .take(metadataByTimestamp.size - MAX_METADATA_ENTRIES)
                .forEach(metadataByTimestamp::remove)
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val timestampNs = image.imageInfo.timestamp
            if (timestampNs - lastAnalyzedTimestampNs < ANALYSIS_INTERVAL_NS) return

            val metadata = metadataByTimestamp.remove(timestampNs)
                ?: latestMetadata?.takeIf {
                    val metadataTimestamp = it.timestampNs ?: return@takeIf false
                    kotlin.math.abs(metadataTimestamp - timestampNs) <= METADATA_TOLERANCE_NS
                }
                ?: return
            val currentConfig = config.get()
            val luminance = measureLuminance(image, currentConfig) ?: return
            val ev = calculateEv100(metadata, luminance, currentConfig.calibrationOffset)
            val configChanged = currentConfig != previousConfig
            val newWeight = if (configChanged) FAST_SMOOTHING_WEIGHT else SMOOTHING_WEIGHT
            val filteredEv = smoothedEv?.let { previous ->
                previous * (1.0 - newWeight) + ev * newWeight
            } ?: ev

            previousConfig = currentConfig
            smoothedEv = filteredEv
            lastAnalyzedTimestampNs = timestampNs
            onResult(
                MeteringResult(
                    ev100 = filteredEv,
                    measuredLuminance = luminance,
                    timestampNs = timestampNs,
                ),
            )
        } finally {
            image.close()
        }
    }

    private fun measureLuminance(
        image: ImageProxy,
        meteringConfig: MeteringConfig,
    ): Double? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val primaryHistogram = IntArray(256)
        val secondaryHistogram = IntArray(256)
        var primarySampleCount = 0
        var secondarySampleCount = 0

        val viewfinder = meteringConfig.viewfinderRect
        val previewAspectRatio = meteringConfig.previewAspectRatio
        val spotCenter = meteringConfig.spotPoint?.let { Pair(it.x, it.y) }
            ?: Pair(viewfinder.centerX, viewfinder.centerY)
        val virtualViewfinderArea =
            viewfinder.width * previewAspectRatio * viewfinder.height
        val spotRadiusSquared = sqrt(
            virtualViewfinderArea * meteringConfig.spotAreaPercent / 100.0 / PI,
        ).let { it * it }
        val centerScale = sqrt(meteringConfig.centerAreaPercent / 100.0)
        val centerHalfWidth = viewfinder.width * centerScale / 2.0
        val centerHalfHeight = viewfinder.height * centerScale / 2.0
        val sampleStep = if (
            meteringConfig.mode == MeteringMode.SPOT &&
            meteringConfig.spotAreaPercent <= 2
        ) {
            FINE_SAMPLE_STEP
        } else {
            SAMPLE_STEP
        }

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    val previewPoint = mapImagePointToPreview(
                        x = x,
                        y = y,
                        rotationDegrees = image.imageInfo.rotationDegrees,
                        imageWidth = width,
                        imageHeight = height,
                        previewAspectRatio = previewAspectRatio,
                    )
                    if (!viewfinder.contains(previewPoint.first, previewPoint.second)) {
                        x += sampleStep
                        continue
                    }
                    val luminance = buffer.get(index).toInt() and 0xFF
                    when (meteringConfig.mode) {
                        MeteringMode.AVERAGE -> {
                            primaryHistogram[luminance]++
                            primarySampleCount++
                        }

                        MeteringMode.SPOT -> {
                            val deltaX =
                                (previewPoint.first - spotCenter.first) * previewAspectRatio
                            val deltaY = previewPoint.second - spotCenter.second
                            if (deltaX * deltaX + deltaY * deltaY <= spotRadiusSquared) {
                                primaryHistogram[luminance]++
                                primarySampleCount++
                            }
                        }

                        MeteringMode.CENTER_WEIGHTED -> {
                            if (
                                abs(previewPoint.first - viewfinder.centerX) <= centerHalfWidth &&
                                abs(previewPoint.second - viewfinder.centerY) <= centerHalfHeight
                            ) {
                                primaryHistogram[luminance]++
                                primarySampleCount++
                            } else {
                                secondaryHistogram[luminance]++
                                secondarySampleCount++
                            }
                        }
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }

        if (primarySampleCount < MIN_SAMPLE_COUNT) return null
        val primaryLuminance = trimmedLinearMean(primaryHistogram, primarySampleCount)
        if (meteringConfig.mode != MeteringMode.CENTER_WEIGHTED) {
            return primaryLuminance
        }
        if (secondarySampleCount < MIN_SAMPLE_COUNT) return null
        val secondaryLuminance = trimmedLinearMean(
            secondaryHistogram,
            secondarySampleCount,
        )
        val centerWeight = meteringConfig.centerWeightPercent / 100.0
        return primaryLuminance * centerWeight +
            secondaryLuminance * (1.0 - centerWeight)
    }

    private fun trimmedLinearMean(
        histogram: IntArray,
        sampleCount: Int,
    ): Double {
        val retainedHistogram = histogram.copyOf()
        var trimLow = (sampleCount * TRIM_RATIO).toInt()
        var trimHigh = trimLow

        for (value in retainedHistogram.indices) {
            if (trimLow > 0) {
                val removed = min(retainedHistogram[value], trimLow)
                retainedHistogram[value] -= removed
                trimLow -= removed
            }
            if (trimLow == 0) break
        }

        for (value in retainedHistogram.lastIndex downTo 0) {
            val removed = min(retainedHistogram[value], trimHigh)
            retainedHistogram[value] -= removed
            trimHigh -= removed
            if (trimHigh == 0) break
        }

        var retained = 0
        var sum = 0.0
        retainedHistogram.forEachIndexed { value, count ->
            val normalized = value / 255.0
            sum += normalized.pow(GAMMA) * count
            retained += count
        }
        return max(sum / retained.coerceAtLeast(1), MIN_LUMINANCE)
    }

    private fun calculateEv100(
        metadata: CameraExposureMetadata,
        luminance: Double,
        calibrationOffset: Double,
    ): Double {
        val exposureSeconds = metadata.exposureTimeNs / 1_000_000_000.0
        val settingEv100 = log2(metadata.aperture * metadata.aperture / exposureSeconds) -
            log2(metadata.sensorSensitivity / 100.0)
        return settingEv100 + log2(luminance / TARGET_LUMINANCE) + calibrationOffset
    }

    private fun mapImagePointToPreview(
        x: Int,
        y: Int,
        rotationDegrees: Int,
        imageWidth: Int,
        imageHeight: Int,
        previewAspectRatio: Double,
    ): Pair<Double, Double> {
        val rawX = x / imageWidth.toDouble()
        val rawY = y / imageHeight.toDouble()
        val (uprightX, uprightY) = when (rotationDegrees) {
            90 -> Pair(1.0 - rawY, rawX)
            180 -> Pair(1.0 - rawX, 1.0 - rawY)
            270 -> Pair(rawY, 1.0 - rawX)
            else -> Pair(rawX, rawY)
        }
        val uprightWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageHeight
        } else {
            imageWidth
        }
        val uprightHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageWidth
        } else {
            imageHeight
        }
        val imageAspectRatio = uprightWidth / uprightHeight.toDouble()

        return if (imageAspectRatio > previewAspectRatio) {
            val visibleWidthFraction = previewAspectRatio / imageAspectRatio
            val crop = (1.0 - visibleWidthFraction) / 2.0
            Pair((uprightX - crop) / visibleWidthFraction, uprightY)
        } else {
            val visibleHeightFraction = imageAspectRatio / previewAspectRatio
            val crop = (1.0 - visibleHeightFraction) / 2.0
            Pair(uprightX, (uprightY - crop) / visibleHeightFraction)
        }
    }

    companion object {
        private const val ANALYSIS_INTERVAL_NS = 100_000_000L
        private const val METADATA_TOLERANCE_NS = 50_000_000L
        private const val MAX_METADATA_ENTRIES = 24
        private const val SAMPLE_STEP = 4
        private const val FINE_SAMPLE_STEP = 2
        private const val MIN_SAMPLE_COUNT = 32
        private const val TRIM_RATIO = 0.05
        private const val GAMMA = 2.2
        private const val TARGET_LUMINANCE = 0.18
        private const val MIN_LUMINANCE = 1e-6
        private const val SMOOTHING_WEIGHT = 0.25
        private const val FAST_SMOOTHING_WEIGHT = 0.60
    }
}
