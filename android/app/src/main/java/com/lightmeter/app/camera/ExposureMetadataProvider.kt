package com.lightmeter.app.camera

data class CameraExposureMetadata(
    val exposureTimeNs: Long,
    val sensorSensitivity: Int,
    val postRawSensitivityBoost: Int = 100,
    val aperture: Double,
    val timestampNs: Long?,
) {
    val effectiveSensitivity: Double
        get() = sensorSensitivity * postRawSensitivityBoost / 100.0
}

data class CameraOptics(
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
)

data class CameraZoomState(
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    val isInitialized: Boolean = false,
)

interface ExposureMetadataProvider {
    fun latestMetadata(): CameraExposureMetadata?
}
