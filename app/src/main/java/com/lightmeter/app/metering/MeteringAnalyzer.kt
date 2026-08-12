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
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
            val configChanged = currentConfig.mode != previousConfig.mode ||
                currentConfig.spotPoint != previousConfig.spotPoint
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
        val histogram = IntArray(256)
        var sampleCount = 0

        val spotCenter = meteringConfig.spotPoint?.let {
            mapPreviewPointToImage(
                point = it,
                rotationDegrees = image.imageInfo.rotationDegrees,
                imageWidth = width,
                imageHeight = height,
            )
        }
        val spotRadius = min(width, height) * SPOT_DIAMETER_RATIO / 2.0
        val spotRadiusSquared = spotRadius * spotRadius

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val insideRoi = meteringConfig.mode == MeteringMode.AVERAGE ||
                    spotCenter == null ||
                    squaredDistance(x, y, spotCenter.first, spotCenter.second) <= spotRadiusSquared
                if (insideRoi) {
                    val index = y * rowStride + x * pixelStride
                    if (index < buffer.limit()) {
                        histogram[buffer.get(index).toInt() and 0xFF]++
                        sampleCount++
                    }
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        if (sampleCount < MIN_SAMPLE_COUNT) return null
        return trimmedLinearMean(histogram, sampleCount)
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

    private fun mapPreviewPointToImage(
        point: NormalizedPoint,
        rotationDegrees: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): Pair<Double, Double> {
        return when (rotationDegrees) {
            90 -> Pair(
                point.y * imageWidth,
                (1.0 - point.x) * imageHeight,
            )
            180 -> Pair(
                (1.0 - point.x) * imageWidth,
                (1.0 - point.y) * imageHeight,
            )
            270 -> Pair(
                (1.0 - point.y) * imageWidth,
                point.x * imageHeight,
            )
            else -> Pair(
                point.x * imageWidth,
                point.y * imageHeight,
            )
        }
    }

    private fun squaredDistance(
        x: Int,
        y: Int,
        centerX: Double,
        centerY: Double,
    ): Double {
        val deltaX = x - centerX
        val deltaY = y - centerY
        return deltaX * deltaX + deltaY * deltaY
    }

    companion object {
        private const val ANALYSIS_INTERVAL_NS = 100_000_000L
        private const val METADATA_TOLERANCE_NS = 50_000_000L
        private const val MAX_METADATA_ENTRIES = 24
        private const val SAMPLE_STEP = 4
        private const val MIN_SAMPLE_COUNT = 32
        private const val SPOT_DIAMETER_RATIO = 0.10
        private const val TRIM_RATIO = 0.05
        private const val GAMMA = 2.2
        private const val TARGET_LUMINANCE = 0.18
        private const val MIN_LUMINANCE = 1e-6
        private const val SMOOTHING_WEIGHT = 0.25
        private const val FAST_SMOOTHING_WEIGHT = 0.60
    }
}
