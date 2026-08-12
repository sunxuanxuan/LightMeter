package com.lightmeter.app.camera

data class CameraExposureMetadata(
    val exposureTimeNs: Long,
    val sensorSensitivity: Int,
    val aperture: Double,
    val timestampNs: Long?,
)

data class CameraOptics(
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
)

interface ExposureMetadataProvider {
    fun latestMetadata(): CameraExposureMetadata?
}
