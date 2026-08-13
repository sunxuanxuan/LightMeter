package com.lightmeter.app.camera

import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.NormalizedMeteringRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class ViewfinderProjection(
    val baseWidthFraction: Double,
    val baseHeightFraction: Double,
) {
    init {
        require(baseWidthFraction > 0.0)
        require(baseHeightFraction > 0.0)
    }

    val fitZoomRatio: Double
        get() = 1.0 / max(baseWidthFraction, baseHeightFraction)

    fun widthFractionAt(zoomRatio: Double): Double {
        return (baseWidthFraction * zoomRatio).coerceIn(0.0, 1.0)
    }

    fun heightFractionAt(zoomRatio: Double): Double {
        return (baseHeightFraction * zoomRatio).coerceIn(0.0, 1.0)
    }

    fun viewfinderAt(zoomRatio: Double): NormalizedMeteringRect {
        val width = widthFractionAt(zoomRatio)
        val height = heightFractionAt(zoomRatio)
        return NormalizedMeteringRect(
            left = (1.0 - width) / 2.0,
            top = (1.0 - height) / 2.0,
            right = (1.0 + width) / 2.0,
            bottom = (1.0 + height) / 2.0,
        )
    }

    fun equivalentFocalLengthAt(
        targetFocalLengthMm: Double,
        zoomRatio: Double,
    ): Double {
        require(targetFocalLengthMm > 0.0)
        require(zoomRatio > 0.0)
        return targetFocalLengthMm * zoomRatio / fitZoomRatio
    }
}

object ViewfinderProjectionCalculator {
    fun supportedFocalLengthRange(
        previewAspectRatio: Double,
        frameFormat: FrameFormat,
        cameraOptics: CameraOptics,
        minimumZoomRatio: Double,
        maximumZoomRatio: Double,
        allowedMinimumFocalLengthMm: Double,
        allowedMaximumFocalLengthMm: Double,
    ): ClosedFloatingPointRange<Double>? {
        require(minimumZoomRatio > 0.0)
        require(maximumZoomRatio >= minimumZoomRatio)
        require(allowedMinimumFocalLengthMm > 0.0)
        require(allowedMaximumFocalLengthMm >= allowedMinimumFocalLengthMm)

        val referenceProjection = calculate(
            previewAspectRatio = previewAspectRatio,
            frameFormat = frameFormat,
            targetFocalLengthMm = 1.0,
            cameraOptics = cameraOptics,
        )
        val hardwareMinimum = ceil(
            referenceProjection.equivalentFocalLengthAt(
                targetFocalLengthMm = 1.0,
                zoomRatio = minimumZoomRatio,
            ),
        )
        val hardwareMaximum = floor(
            referenceProjection.equivalentFocalLengthAt(
                targetFocalLengthMm = 1.0,
                zoomRatio = maximumZoomRatio,
            ),
        )
        val minimum = max(allowedMinimumFocalLengthMm, hardwareMinimum)
        val maximum = min(allowedMaximumFocalLengthMm, hardwareMaximum)
        return if (minimum <= maximum) minimum..maximum else null
    }

    fun calculate(
        previewAspectRatio: Double,
        frameFormat: FrameFormat,
        targetFocalLengthMm: Double,
        cameraOptics: CameraOptics,
    ): ViewfinderProjection {
        require(previewAspectRatio > 0.0)
        require(targetFocalLengthMm > 0.0)

        val sensorPortraitWidth = min(
            cameraOptics.sensorWidthMm,
            cameraOptics.sensorHeightMm,
        )
        val sensorPortraitHeight = max(
            cameraOptics.sensorWidthMm,
            cameraOptics.sensorHeightMm,
        )
        val sensorAspectRatio = sensorPortraitWidth / sensorPortraitHeight

        val displayedSensorWidth: Double
        val displayedSensorHeight: Double
        if (previewAspectRatio >= sensorAspectRatio) {
            displayedSensorWidth = sensorPortraitWidth
            displayedSensorHeight = sensorPortraitWidth / previewAspectRatio
        } else {
            displayedSensorHeight = sensorPortraitHeight
            displayedSensorWidth = sensorPortraitHeight * previewAspectRatio
        }

        val targetPortraitWidth = min(
            frameFormat.frameWidthMm,
            frameFormat.frameHeightMm,
        )
        val targetPortraitHeight = max(
            frameFormat.frameWidthMm,
            frameFormat.frameHeightMm,
        )
        val targetProjectionWidth =
            cameraOptics.focalLengthMm * targetPortraitWidth / targetFocalLengthMm
        val targetProjectionHeight =
            cameraOptics.focalLengthMm * targetPortraitHeight / targetFocalLengthMm

        return ViewfinderProjection(
            baseWidthFraction = targetProjectionWidth / displayedSensorWidth,
            baseHeightFraction = targetProjectionHeight / displayedSensorHeight,
        )
    }
}
