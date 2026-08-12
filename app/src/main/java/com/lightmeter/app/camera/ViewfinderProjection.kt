package com.lightmeter.app.camera

import com.lightmeter.app.exposure.FrameFormat
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
}

object ViewfinderProjectionCalculator {
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
