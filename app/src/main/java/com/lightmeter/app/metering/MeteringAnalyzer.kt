package com.lightmeter.app.metering

import android.graphics.Rect
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
    private val configLock = Any()
    private val fallbackAperture = AtomicReference<Double?>(null)
    private val exposureSnapshot = AtomicReference<ExposureSnapshot?>(null)
    private val metadataByTimestamp = ConcurrentHashMap<Long, CameraExposureMetadata>()

    private var lastAnalyzedTimestampNs = 0L
    private var smoothedEv: Double? = null
    private var previousConfig = initialConfig

    fun updateConfig(newConfig: MeteringConfig) {
        synchronized(configLock) {
            val oldConfig = config.getAndSet(newConfig)
            if (oldConfig != newConfig) {
                exposureSnapshot.set(null)
            }
        }
    }

    fun updateFallbackAperture(aperture: Double?) {
        fallbackAperture.set(aperture?.takeIf { it > 0.0 })
    }

    fun latestExposureSnapshot(): ExposureSnapshot? = exposureSnapshot.get()

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
        val postRawSensitivityBoost = result.get(
            CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST,
        )
            ?.takeIf { it > 0 }
            ?: 100
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?.toDouble()
            ?.takeIf { it > 0.0 }
            ?: fallbackAperture.get()
            ?: return

        val metadata = CameraExposureMetadata(
            exposureTimeNs = exposureTimeNs,
            sensorSensitivity = sensorSensitivity,
            postRawSensitivityBoost = postRawSensitivityBoost,
            aperture = aperture,
            timestampNs = timestamp,
        )
        metadataByTimestamp[timestamp] = metadata

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

            val metadata = metadataForTimestamp(timestampNs) ?: return
            val currentConfig = config.get()
            if (!currentConfig.isZoomReady) return
            val luminance = measureLuminance(image, currentConfig) ?: return
            val ev = calculateEv100(metadata, luminance, currentConfig.calibrationOffset)
            val currentExposureMap = createExposureMap(image, metadata, currentConfig)
            synchronized(configLock) {
                if (config.get() != currentConfig) return
                currentExposureMap?.let { map ->
                    exposureSnapshot.set(
                        ExposureSnapshot(
                            exposureMap = map,
                            meteredEv100 = ev,
                            timestampNs = timestampNs,
                            revision = currentConfig.revision,
                        ),
                    )
                }
                val configChanged = currentConfig != previousConfig
                val filteredEv = if (configChanged) {
                    ev
                } else {
                    smoothedEv?.let { previous ->
                        previous * (1.0 - SMOOTHING_WEIGHT) + ev * SMOOTHING_WEIGHT
                    } ?: ev
                }

                previousConfig = currentConfig
                smoothedEv = filteredEv
                lastAnalyzedTimestampNs = timestampNs
                onResult(
                    MeteringResult(
                        ev100 = filteredEv,
                        measuredLuminance = luminance,
                        timestampNs = timestampNs,
                        revision = currentConfig.revision,
                    ),
                )
            }
        } finally {
            image.close()
        }
    }

    private fun metadataForTimestamp(timestampNs: Long): CameraExposureMetadata? {
        metadataByTimestamp.remove(timestampNs)?.let { return it }
        val nearestTimestamp = metadataByTimestamp.keys
            .minByOrNull { abs(it - timestampNs) }
            ?.takeIf { abs(it - timestampNs) <= METADATA_TOLERANCE_NS }
            ?: return null
        return metadataByTimestamp.remove(nearestTimestamp)
    }

    private fun measureLuminance(
        image: ImageProxy,
        meteringConfig: MeteringConfig,
    ): Double? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val cropRect = image.cropRect
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

        var y = cropRect.top
        while (y < cropRect.bottom) {
            var x = cropRect.left
            while (x < cropRect.right) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    val previewPoint = mapImagePointToPreview(
                        x = x,
                        y = y,
                        rotationDegrees = image.imageInfo.rotationDegrees,
                        cropRect = cropRect,
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
        val settingEv100 = cameraSettingEv100(metadata, exposureSeconds)
        return settingEv100 + log2(luminance / TARGET_LUMINANCE) + calibrationOffset
    }

    private fun createExposureMap(
        image: ImageProxy,
        metadata: CameraExposureMetadata,
        meteringConfig: MeteringConfig,
    ): ExposureMap? {
        val plane = image.planes.firstOrNull() ?: return null
        val cropRect = image.cropRect
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return null

        val previewAspectRatio = meteringConfig.previewAspectRatio
        val mapWidth: Int
        val mapHeight: Int
        if (previewAspectRatio <= 1.0) {
            mapHeight = EXPOSURE_MAP_LONG_EDGE
            mapWidth = (mapHeight * previewAspectRatio).toInt().coerceAtLeast(1)
        } else {
            mapWidth = EXPOSURE_MAP_LONG_EDGE
            mapHeight = (mapWidth / previewAspectRatio).toInt().coerceAtLeast(1)
        }

        val exposureSeconds = metadata.exposureTimeNs / 1_000_000_000.0
        val settingEv100 = cameraSettingEv100(metadata, exposureSeconds)
        val pixelEv100 = FloatArray(mapWidth * mapHeight)
        val rawLuminanceMap = ByteArray(mapWidth * mapHeight)
        val clippedHighlights = BooleanArray(mapWidth * mapHeight)
        val buffer = plane.buffer

        for (mapY in 0 until mapHeight) {
            val previewY = (mapY + 0.5) / mapHeight
            for (mapX in 0 until mapWidth) {
                val previewX = (mapX + 0.5) / mapWidth
                if (!meteringConfig.viewfinderRect.contains(previewX, previewY)) {
                    pixelEv100[mapY * mapWidth + mapX] = Float.NaN
                    continue
                }
                val imagePoint = mapPreviewPointToImage(
                    previewX = previewX,
                    previewY = previewY,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    cropRect = cropRect,
                )
                val index = imagePoint.second * plane.rowStride +
                    imagePoint.first * plane.pixelStride
                val rawLuminance = if (index < buffer.limit()) {
                    buffer.get(index).toInt() and 0xFF
                } else {
                    0
                }
                rawLuminanceMap[mapY * mapWidth + mapX] = rawLuminance.toByte()
                val linearLuminance = linearLuminance(rawLuminance)
                clippedHighlights[mapY * mapWidth + mapX] =
                    rawLuminance >= HIGHLIGHT_CLIP_LEVEL
                pixelEv100[mapY * mapWidth + mapX] = (
                    settingEv100 +
                        log2(linearLuminance / TARGET_LUMINANCE) +
                        meteringConfig.calibrationOffset
                    ).toFloat()
            }
        }

        return ExposureMap(
            width = mapWidth,
            height = mapHeight,
            pixelEv100 = pixelEv100,
            rawLuminance = rawLuminanceMap,
            clippedHighlights = clippedHighlights,
            cameraSettingEv100 = settingEv100,
            timestampNs = image.imageInfo.timestamp,
            revision = meteringConfig.revision,
        )
    }

    private fun cameraSettingEv100(
        metadata: CameraExposureMetadata,
        exposureSeconds: Double,
    ): Double {
        return log2(metadata.aperture * metadata.aperture / exposureSeconds) -
            log2(metadata.effectiveSensitivity / 100.0)
    }

    private fun mapImagePointToPreview(
        x: Int,
        y: Int,
        rotationDegrees: Int,
        cropRect: Rect,
    ): Pair<Double, Double> {
        val rawX = (x - cropRect.left) / cropRect.width().toDouble()
        val rawY = (y - cropRect.top) / cropRect.height().toDouble()
        return when (rotationDegrees) {
            90 -> Pair(1.0 - rawY, rawX)
            180 -> Pair(1.0 - rawX, 1.0 - rawY)
            270 -> Pair(rawY, 1.0 - rawX)
            else -> Pair(rawX, rawY)
        }
    }

    private fun mapPreviewPointToImage(
        previewX: Double,
        previewY: Double,
        rotationDegrees: Int,
        cropRect: Rect,
    ): Pair<Int, Int> {
        val (rawX, rawY) = when (rotationDegrees) {
            90 -> Pair(previewY, 1.0 - previewX)
            180 -> Pair(1.0 - previewX, 1.0 - previewY)
            270 -> Pair(1.0 - previewY, previewX)
            else -> Pair(previewX, previewY)
        }
        val x = (cropRect.left + rawX * cropRect.width())
            .toInt()
            .coerceIn(cropRect.left, cropRect.right - 1)
        val y = (cropRect.top + rawY * cropRect.height())
            .toInt()
            .coerceIn(cropRect.top, cropRect.bottom - 1)
        return Pair(x, y)
    }

    private fun linearLuminance(rawLuminance: Int): Double {
        return max((rawLuminance / 255.0).pow(GAMMA), MIN_LUMINANCE)
    }

    companion object {
        private const val ANALYSIS_INTERVAL_NS = 100_000_000L
        private const val METADATA_TOLERANCE_NS = 50_000_000L
        private const val MAX_METADATA_ENTRIES = 24
        private const val EXPOSURE_MAP_LONG_EDGE = 240
        private const val SAMPLE_STEP = 4
        private const val FINE_SAMPLE_STEP = 2
        private const val MIN_SAMPLE_COUNT = 32
        private const val TRIM_RATIO = 0.05
        private const val GAMMA = 2.2
        private const val TARGET_LUMINANCE = 0.18
        private const val HIGHLIGHT_CLIP_LEVEL = 235
        private const val MIN_LUMINANCE = 1e-6
        private const val SMOOTHING_WEIGHT = 0.25
    }
}
