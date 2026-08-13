package com.lightmeter.app.filmpreview

import kotlin.math.log2

enum class PreviewSceneRating(val displayName: String) {
    GOOD("良好"),
    CAUTION("注意"),
    POOR("较差"),
    UNAVAILABLE("不可判断"),
}

enum class PreviewAdviceCode {
    SUITABLE,
    AMBIENT_TOO_DARK,
    AMBIENT_TOO_BRIGHT,
    USE_FLASH,
    UNAVAILABLE,
}

data class FilmPreviewEvaluation(
    val presetEv100: Double,
    val sceneDeltaEv: Double?,
    val rating: PreviewSceneRating,
    val adviceCode: PreviewAdviceCode,
)

object FilmPreviewEngine {
    fun presetEv100(preset: DisposableCameraPreset): Double {
        return log2(
            preset.optics.aperture * preset.optics.aperture /
                preset.shutterSeconds,
        ) - log2(preset.film.iso / 100.0)
    }

    fun pixelDeltaEv(pixelEv100: Double, preset: DisposableCameraPreset): Double {
        require(pixelEv100.isFinite())
        return pixelEv100 - presetEv100(preset)
    }

    fun evaluate(
        meteredEv100: Double?,
        preset: DisposableCameraPreset,
    ): FilmPreviewEvaluation {
        val presetEv100 = presetEv100(preset)
        if (meteredEv100 == null || !meteredEv100.isFinite()) {
            return FilmPreviewEvaluation(
                presetEv100 = presetEv100,
                sceneDeltaEv = null,
                rating = PreviewSceneRating.UNAVAILABLE,
                adviceCode = PreviewAdviceCode.UNAVAILABLE,
            )
        }

        val deltaEv = meteredEv100 - presetEv100
        val shadowLimit = -preset.film.shadowLatitudeStops
        val highlightLimit = preset.film.highlightLatitudeStops
        val rating = when {
            deltaEv <= shadowLimit - POOR_MARGIN_STOPS + COMPARISON_EPSILON ||
                deltaEv >= highlightLimit + POOR_MARGIN_STOPS -
                COMPARISON_EPSILON -> PreviewSceneRating.POOR

            deltaEv <= shadowLimit + CAUTION_MARGIN_STOPS + COMPARISON_EPSILON ||
                deltaEv >= highlightLimit - CAUTION_MARGIN_STOPS -
                COMPARISON_EPSILON -> PreviewSceneRating.CAUTION

            else -> PreviewSceneRating.GOOD
        }
        val advice = when {
            deltaEv <= shadowLimit + COMPARISON_EPSILON &&
                preset.flash != null -> PreviewAdviceCode.USE_FLASH
            deltaEv <= shadowLimit + COMPARISON_EPSILON -> PreviewAdviceCode.AMBIENT_TOO_DARK
            deltaEv >= highlightLimit - COMPARISON_EPSILON ->
                PreviewAdviceCode.AMBIENT_TOO_BRIGHT
            else -> PreviewAdviceCode.SUITABLE
        }
        return FilmPreviewEvaluation(
            presetEv100 = presetEv100,
            sceneDeltaEv = deltaEv,
            rating = rating,
            adviceCode = advice,
        )
    }

    private const val CAUTION_MARGIN_STOPS = 1.0
    private const val POOR_MARGIN_STOPS = 1.0
    private const val COMPARISON_EPSILON = 1e-9
}
