package com.lightmeter.app.exposure

enum class FramePreset(
    val displayName: String,
    val safeShutterSeconds: Double,
    val frameWidthMm: Double,
    val frameHeightMm: Double,
    val focalLengthMm: Double,
) {
    FILM_135_35MM("135 · 35mm", 1.0 / 30.0, 36.0, 24.0, 35.0),
    FILM_135_50MM("135 · 50mm", 1.0 / 60.0, 36.0, 24.0, 50.0),
    FILM_135_70MM("135 · 70mm", 1.0 / 125.0, 36.0, 24.0, 70.0),
    APS_C_50MM("APS-C · 50mm", 1.0 / 60.0, 23.6, 15.7, 50.0),
    FILM_645_75MM("6×4.5 · 75mm", 1.0 / 125.0, 56.0, 41.5, 75.0),
}

data class ExposurePair(
    val apertureLabel: String,
    val aperture: Double,
    val shutterLabel: String,
    val shutterSeconds: Double,
    val ev: Double,
    val error: Double,
)

data class ExposureRecommendation(
    val ev100Metered: Double,
    val evTarget: Double,
    val iso: Int,
    val exposureCompensation: Double,
    val primary: ExposurePair,
    val equivalents: List<ExposurePair>,
)
