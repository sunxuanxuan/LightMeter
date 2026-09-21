package com.lightmeter.app.metering

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
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
    val deriveExposureFromBitmap: Boolean = false,
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
            val timestampRegressed = lastAnalyzedTimestampNs > 0L &&
                timestampNs < lastAnalyzedTimestampNs
            if (
                captureRequestId == 0 &&
                !timestampRegressed &&
                timestampNs - lastAnalyzedTimestampNs < ANALYSIS_INTERVAL_NS
            ) {
                return
            }

            val metadata = metadataForTimestamp(timestampNs) ?: return
            val currentConfig = config.get()
            val luminanceRange = luminanceRangeFor(image)
            val luminance = measureLuminance(image, currentConfig, luminanceRange) ?: return
            val exposureSeconds = metadata.exposureTimeNs / 1_000_000_000.0
            val cameraSettingEv100 = cameraSettingEv100(metadata, exposureSeconds)
            val ev = cameraSettingEv100 +
                log2(luminance / TARGET_LUMINANCE) +
                currentConfig.calibrationOffset
            var mapDurationMs = 0L
            var bitmapDurationMs = 0L
            val currentSnapshot: ExposureSnapshot?
            val capturedBitmap: Bitmap?
            if (captureRequestId > 0) {
                val mapStartedAtNs = SystemClock.elapsedRealtimeNanos()
                val currentExposureMap =
                    createExposureMap(image, metadata, currentConfig, luminanceRange) ?: return
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
                val shouldResetSmoothing = currentConfig != previousConfig ||
                    timestampRegressed
                val filteredEv = if (shouldResetSmoothing) {
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
                        cameraSettingEv100 = cameraSettingEv100,
                        calibrationOffset = currentConfig.calibrationOffset,
                    ),
                )
            }
        } finally {
            image.close()
        }
    }

    private fun createCapturedBitmap(image: ImageProxy): Bitmap? {
        return runCatching { ImageProxyBitmapConverter.convert(image) }.getOrNull()
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
        luminanceRange: YuvLuminanceRange,
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
        val circularAreaPercent = if (meteringConfig.mode == MeteringMode.CENTER_AVERAGE) {
            meteringConfig.centerAverageAreaPercent
        } else {
            meteringConfig.spotAreaPercent
        }
        val spotRadiusSquared = sqrt(
            virtualViewfinderArea * circularAreaPercent / 100.0 / PI,
        ).let { it * it }
        val centerScale = sqrt(meteringConfig.centerAreaPercent / 100.0)
        val centerHalfWidth = viewfinder.width * centerScale / 2.0
        val centerHalfHeight = viewfinder.height * centerScale / 2.0
        val centerCropScale = sqrt(meteringConfig.centerCropPercent / 100.0)
        val centerCropHalfWidth = viewfinder.width * centerCropScale / 2.0
        val centerCropHalfHeight = viewfinder.height * centerCropScale / 2.0
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

                        MeteringMode.CENTER_CROP_AVERAGE -> {
                            if (
                                abs(previewPoint.first - viewfinder.centerX) <= centerCropHalfWidth &&
                                abs(previewPoint.second - viewfinder.centerY) <= centerCropHalfHeight
                            ) {
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
        val primaryLuminance = if (meteringConfig.mode == MeteringMode.CENTER_CROP_AVERAGE) {
            geometricLuminanceMean(primaryHistogram, luminanceRange)
        } else {
            trimmedLinearMean(
                histogram = primaryHistogram,
                sampleCount = primarySampleCount,
                luminanceRange = luminanceRange,
            )
        }
        if (meteringConfig.mode != MeteringMode.CENTER_WEIGHTED) {
            return primaryLuminance
        }
        if (secondarySampleCount < MIN_SAMPLE_COUNT) return null
        val secondaryLuminance = trimmedLinearMean(
            secondaryHistogram,
            secondarySampleCount,
            luminanceRange,
        )
        val centerWeight = meteringConfig.centerWeightPercent / 100.0
        return primaryLuminance * centerWeight +
            secondaryLuminance * (1.0 - centerWeight)
    }

    private fun geometricLuminanceMean(
        histogram: IntArray,
        luminanceRange: YuvLuminanceRange,
    ): Double {
        var sampleCount = 0
        var logLuminanceSum = 0.0
        histogram.forEachIndexed { rawLuminance, count ->
            if (count == 0) return@forEachIndexed
            logLuminanceSum += kotlin.math.ln(
                YuvLuminance.linear(rawLuminance, luminanceRange)
                    .coerceAtLeast(LUMINANCE_EPSILON),
            ) * count
            sampleCount += count
        }
        return kotlin.math.exp(logLuminanceSum / sampleCount.coerceAtLeast(1))
    }

    private fun trimmedLinearMean(
        histogram: IntArray,
        sampleCount: Int,
        luminanceRange: YuvLuminanceRange,
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
            sum += YuvLuminance.linear(value, luminanceRange) * count
            retained += count
        }
        return sum / retained.coerceAtLeast(1)
    }

    private fun createExposureMap(
        image: ImageProxy,
        metadata: CameraExposureMetadata,
        meteringConfig: MeteringConfig,
        luminanceRange: YuvLuminanceRange,
    ): ExposureMap? {
        val plane = image.planes.firstOrNull() ?: return null
        val cropRect = image.cropRect
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return null

        val rotationDegrees = image.imageInfo.rotationDegrees
        val isQuarterTurn = rotationDegrees == 90 || rotationDegrees == 270
        val rotatedWidth = if (isQuarterTurn) cropRect.height() else cropRect.width()
        val rotatedHeight = if (isQuarterTurn) cropRect.width() else cropRect.height()
        val mapScale = (
            EXPOSURE_MAP_LONG_EDGE_PX / max(rotatedWidth, rotatedHeight).toDouble()
            ).coerceAtMost(1.0)
        val mapWidth = (rotatedWidth * mapScale).roundToInt().coerceAtLeast(1)
        val mapHeight = (rotatedHeight * mapScale).roundToInt().coerceAtLeast(1)

        val exposureSeconds = metadata.exposureTimeNs / 1_000_000_000.0
        val settingEv100 = cameraSettingEv100(metadata, exposureSeconds)
        val pixelEv100 = FloatArray(mapWidth * mapHeight)
        val clippedHighlights = BooleanArray(mapWidth * mapHeight)
        val buffer = plane.buffer
        val evByLuminance = FloatArray(256) { rawLuminance ->
            (
                settingEv100 +
                    log2(
                        YuvLuminance.linear(rawLuminance, luminanceRange) / TARGET_LUMINANCE,
                    ) +
                    meteringConfig.calibrationOffset
                ).toFloat()
        }
        val clippedByLuminance = BooleanArray(256) { rawLuminance ->
            YuvLuminance.isHighlightClipped(rawLuminance, luminanceRange)
        }

        for (mapY in 0 until mapHeight) {
            for (mapX in 0 until mapWidth) {
                val mapIndex = mapY * mapWidth + mapX
                val rotatedX = (
                    (mapX + 0.5) * rotatedWidth / mapWidth
                    ).toInt().coerceIn(0, rotatedWidth - 1)
                val rotatedY = (
                    (mapY + 0.5) * rotatedHeight / mapHeight
                    ).toInt().coerceIn(0, rotatedHeight - 1)
                val sourceX: Int
                val sourceY: Int
                when (rotationDegrees) {
                    90 -> {
                        sourceX = cropRect.left + rotatedY
                        sourceY = cropRect.bottom - rotatedX - 1
                    }
                    180 -> {
                        sourceX = cropRect.right - rotatedX - 1
                        sourceY = cropRect.bottom - rotatedY - 1
                    }
                    270 -> {
                        sourceX = cropRect.right - rotatedY - 1
                        sourceY = cropRect.top + rotatedX
                    }
                    else -> {
                        sourceX = cropRect.left + rotatedX
                        sourceY = cropRect.top + rotatedY
                    }
                }
                val index = sourceY * plane.rowStride + sourceX * plane.pixelStride
                val rawLuminance = buffer.get(index).toInt() and 0xFF
                clippedHighlights[mapIndex] = clippedByLuminance[rawLuminance]
                pixelEv100[mapIndex] = evByLuminance[rawLuminance]
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

    private fun luminanceRangeFor(image: ImageProxy): YuvLuminanceRange {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            image.image?.let { sourceImage ->
                YuvLuminance.rangeForDataSpace(sourceImage.dataSpace)?.let { return it }
            }
        }
        // Older Android APIs do not expose the producer data space on Image.
        return YuvLuminanceRange.LIMITED
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

    companion object {
        private const val TAG = "MeteringAnalyzer"
        private const val ANALYSIS_INTERVAL_NS = 200_000_000L
        private const val METADATA_TOLERANCE_NS = 50_000_000L
        private const val MAX_METADATA_ENTRIES = 24
        private const val SAMPLE_STEP = 4
        private const val FINE_SAMPLE_STEP = 2
        private const val MIN_SAMPLE_COUNT = 32
        private const val EXPOSURE_MAP_LONG_EDGE_PX = 480
        private const val TRIM_RATIO = 0.05
        private const val TARGET_LUMINANCE = 0.18
        private const val LUMINANCE_EPSILON = 1e-6
        private const val SMOOTHING_WEIGHT = 0.44
    }
}
