package com.lightmeter.app.exposure

enum class FrameFormat(
    val displayName: String,
    val frameWidthMm: Double,
    val frameHeightMm: Double,
) {
    FILM_135("135", 36.0, 24.0),
    APS_C("APS-C", 23.6, 15.7),
    FILM_645("6×4.5", 56.0, 41.5),
    FILM_66("6×6", 56.0, 56.0),
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
