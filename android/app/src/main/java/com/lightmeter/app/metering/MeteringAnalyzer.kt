package com.lightmeter.app.metering

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.camera.CameraExposureMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CapturedExposureFrame(
    val requestId: Int,
    val bitmap: Bitmap,
    val snapshot: ExposureSnapshot,
)

class MeteringAnalyzer(
    initialConfig: MeteringConfig = MeteringConfig(),
    private val onResult: (MeteringResult) -> Unit,
) : CameraCaptureSession.CaptureCallback(), ImageAnalysis.Analyzer {
    private val config = AtomicReference(initialConfig)
    private val configLock = Any()
    private val fallbackAperture = AtomicReference<Double?>(null)
    private val pendingFrameCaptureRequest = AtomicInteger(0)
    private val capturedExposureFrame = AtomicReference<CapturedExposureFrame?>(null)
    private val metadataByTimestamp = ConcurrentHashMap<Long, CameraExposureMetadata>()

    private var lastAnalyzedTimestampNs = 0L
    private var smoothedEv: Double? = null
    private var previousConfig = initialConfig

    fun updateConfig(newConfig: MeteringConfig) {
        synchronized(configLock) {
            config.set(newConfig)
        }
    }

    fun updateFallbackAperture(aperture: Double?) {
        fallbackAperture.set(aperture?.takeIf { it > 0.0 })
    }

    fun requestFrameCapture(requestId: Int) {
        require(requestId > 0)
        capturedExposureFrame.getAndSet(null)?.bitmap?.recycle()
        pendingFrameCaptureRequest.set(requestId)
    }

    fun takeCapturedFrame(requestId: Int): CapturedExposureFrame? {
        val captured = capturedExposureFrame.get() ?: return null
        if (captured.requestId != requestId) return null
        return if (capturedExposureFrame.compareAndSet(captured, null)) captured else null
    }

    fun cancelFrameCapture(requestId: Int) {
        pendingFrameCaptureRequest.compareAndSet(requestId, 0)
        while (true) {
            val captured = capturedExposureFrame.get() ?: return
            if (captured.requestId != requestId) return
            if (capturedExposureFrame.compareAndSet(captured, null)) {
                captured.bitmap.recycle()
                return
            }
        }
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
            val captureRequestId = pendingFrameCaptureRequest.get()
            if (
                captureRequestId == 0 &&
                timestampNs - lastAnalyzedTimestampNs < ANALYSIS_INTERVAL_NS
            ) {
                return
            }

            val metadata = metadataForTimestamp(timestampNs) ?: return
            val currentConfig = config.get()
            if (!currentConfig.isZoomReady) return
            val luminance = measureLuminance(image, currentConfig) ?: return
            val ev = calculateEv100(metadata, luminance, currentConfig.calibrationOffset)
            var mapDurationMs = 0L
            var bitmapDurationMs = 0L
            val currentSnapshot: ExposureSnapshot?
            val capturedBitmap: Bitmap?
            if (captureRequestId > 0) {
                val mapStartedAtNs = SystemClock.elapsedRealtimeNanos()
                val currentExposureMap =
                    createExposureMap(image, metadata, currentConfig) ?: return
                mapDurationMs = (
                    SystemClock.elapsedRealtimeNanos() - mapStartedAtNs
                    ) / 1_000_000
                currentSnapshot = ExposureSnapshot(
                    exposureMap = currentExposureMap,
                    meteredEv100 = ev,
                    timestampNs = timestampNs,
                    revision = currentConfig.revision,
                )
                val bitmapStartedAtNs = SystemClock.elapsedRealtimeNanos()
                capturedBitmap = createCapturedBitmap(image)
                bitmapDurationMs = (
                    SystemClock.elapsedRealtimeNanos() - bitmapStartedAtNs
                    ) / 1_000_000
            } else {
                currentSnapshot = null
                capturedBitmap = null
            }
            if (BuildConfig.DEBUG && captureRequestId > 0) {
                val exposureMap = requireNotNull(currentSnapshot).exposureMap
                Log.d(
                    TAG,
                    (
                        "image=%dx%d crop=%s rotation=%d map=%dx%d mapMs=%d " +
                            "bitmap=%s bitmapMs=%d"
                        ).format(
                            image.width,
                            image.height,
                            image.cropRect.toShortString(),
                            image.imageInfo.rotationDegrees,
                            exposureMap.width,
                            exposureMap.height,
                            mapDurationMs,
                            capturedBitmap?.let { "${it.width}x${it.height}" } ?: "null",
                            bitmapDurationMs,
                        ),
                )
            }
            synchronized(configLock) {
                if (config.get() != currentConfig) {
                    capturedBitmap?.recycle()
                    return
                }
                if (
                    captureRequestId > 0 &&
                    capturedBitmap != null &&
                    currentSnapshot != null &&
                    pendingFrameCaptureRequest.compareAndSet(captureRequestId, 0)
                ) {
                    capturedExposureFrame.getAndSet(
                        CapturedExposureFrame(
                            requestId = captureRequestId,
                            bitmap = capturedBitmap,
                            snapshot = currentSnapshot,
                        ),
                    )?.bitmap?.recycle()
                } else {
                    capturedBitmap?.recycle()
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

    private fun createCapturedBitmap(image: ImageProxy): Bitmap? {
        return runCatching {
            val source = image.toBitmap()
            val cropRect = image.cropRect
            val safeLeft = cropRect.left.coerceIn(0, source.width - 1)
            val safeTop = cropRect.top.coerceIn(0, source.height - 1)
            val safeRight = cropRect.right.coerceIn(safeLeft + 1, source.width)
            val safeBottom = cropRect.bottom.coerceIn(safeTop + 1, source.height)
            val cropped = if (
                safeLeft == 0 &&
                safeTop == 0 &&
                safeRight == source.width &&
                safeBottom == source.height
            ) {
                source
            } else {
                Bitmap.createBitmap(
                    source,
                    safeLeft,
                    safeTop,
                    safeRight - safeLeft,
                    safeBottom - safeTop,
                ).also { source.recycle() }
            }
            val rotationDegrees = image.imageInfo.rotationDegrees
            if (rotationDegrees == 0) {
                cropped
            } else {
                Bitmap.createBitmap(
                    cropped,
                    0,
                    0,
                    cropped.width,
                    cropped.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                    true,
                ).also { rotated ->
                    if (rotated !== cropped) cropped.recycle()
                }
            }
        }.getOrNull()
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

                        MeteringMode.SPOT,
                        MeteringMode.CENTER_AVERAGE -> {
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
            sum += YuvLuminance.linear(value) * count
            retained += count
        }
        return sum / retained.coerceAtLeast(1)
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
        val clippedHighlights = BooleanArray(mapWidth * mapHeight)
        val buffer = plane.buffer
        val sampleOffsets = doubleArrayOf(0.25, 0.75)

        for (mapY in 0 until mapHeight) {
            for (mapX in 0 until mapWidth) {
                val mapIndex = mapY * mapWidth + mapX
                val centerX = (mapX + 0.5) / mapWidth
                val centerY = (mapY + 0.5) / mapHeight
                if (!meteringConfig.viewfinderRect.contains(centerX, centerY)) {
                    pixelEv100[mapIndex] = Float.NaN
                    continue
                }

                var linearLuminanceSum = 0.0
                var clippedSampleCount = 0
                var sampleCount = 0
                for (offsetY in sampleOffsets) {
                    val previewY = (mapY + offsetY) / mapHeight
                    for (offsetX in sampleOffsets) {
                        val previewX = (mapX + offsetX) / mapWidth
                        if (!meteringConfig.viewfinderRect.contains(previewX, previewY)) {
                            continue
                        }
                        val index = mapPreviewPointToBufferIndex(
                            previewX = previewX,
                            previewY = previewY,
                            rotationDegrees = image.imageInfo.rotationDegrees,
                            cropRect = cropRect,
                            rowStride = plane.rowStride,
                            pixelStride = plane.pixelStride,
                        )
                        if (index !in 0 until buffer.limit()) continue
                        val rawLuminance = buffer.get(index).toInt() and 0xFF
                        linearLuminanceSum += YuvLuminance.linear(rawLuminance)
                        if (YuvLuminance.isHighlightClipped(rawLuminance)) {
                            clippedSampleCount++
                        }
                        sampleCount++
                    }
                }
                if (sampleCount == 0) {
                    pixelEv100[mapIndex] = Float.NaN
                    continue
                }

                clippedHighlights[mapIndex] =
                    clippedSampleCount / sampleCount.toDouble() >= CLIPPED_SAMPLE_RATIO
                val linearLuminance = linearLuminanceSum / sampleCount
                pixelEv100[mapIndex] = (
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
            clippedHighlights = clippedHighlights,
            cameraSettingEv100 = settingEv100,
            calibrationOffset = meteringConfig.calibrationOffset,
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

    private fun mapPreviewPointToBufferIndex(
        previewX: Double,
        previewY: Double,
        rotationDegrees: Int,
        cropRect: Rect,
        rowStride: Int,
        pixelStride: Int,
    ): Int {
        val rawX: Double
        val rawY: Double
        when (rotationDegrees) {
            90 -> {
                rawX = previewY
                rawY = 1.0 - previewX
            }
            180 -> {
                rawX = 1.0 - previewX
                rawY = 1.0 - previewY
            }
            270 -> {
                rawX = 1.0 - previewY
                rawY = previewX
            }
            else -> {
                rawX = previewX
                rawY = previewY
            }
        }
        val x = (cropRect.left + rawX * cropRect.width())
            .toInt()
            .coerceIn(cropRect.left, cropRect.right - 1)
        val y = (cropRect.top + rawY * cropRect.height())
            .toInt()
            .coerceIn(cropRect.top, cropRect.bottom - 1)
        return y * rowStride + x * pixelStride
    }

    companion object {
        private const val TAG = "MeteringAnalyzer"
        private const val ANALYSIS_INTERVAL_NS = 200_000_000L
        private const val METADATA_TOLERANCE_NS = 50_000_000L
        private const val MAX_METADATA_ENTRIES = 24
        private const val EXPOSURE_MAP_LONG_EDGE = 480
        private const val CLIPPED_SAMPLE_RATIO = 0.25
        private const val SAMPLE_STEP = 4
        private const val FINE_SAMPLE_STEP = 2
        private const val MIN_SAMPLE_COUNT = 32
        private const val TRIM_RATIO = 0.05
        private const val TARGET_LUMINANCE = 0.18
        private const val SMOOTHING_WEIGHT = 0.44
    }
}
