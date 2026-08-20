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
    SEEK_SHADE,
    UNAVAILABLE,
}

data class FilmPreviewEvaluation(
    val presetEv100: Double,
    val sceneDeltaEv: Double?,
    val rating: PreviewSceneRating,
    val adviceCode: PreviewAdviceCode,
) {
    val baseEv100: Double get() = presetEv100
    val realEv100: Double? get() = sceneDeltaEv?.plus(presetEv100)
}

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
        val rating = when {
            kotlin.math.abs(deltaEv) <= NORMAL_DELTA_EV -> PreviewSceneRating.GOOD
            kotlin.math.abs(deltaEv) <= CAUTION_DELTA_EV -> PreviewSceneRating.CAUTION
            else -> PreviewSceneRating.POOR
        }
        val advice = when {
            rating == PreviewSceneRating.GOOD -> PreviewAdviceCode.SUITABLE
            deltaEv < 0.0 && rating == PreviewSceneRating.POOR && preset.flash != null ->
                PreviewAdviceCode.USE_FLASH
            deltaEv < 0.0 -> PreviewAdviceCode.AMBIENT_TOO_DARK
            rating == PreviewSceneRating.POOR -> PreviewAdviceCode.SEEK_SHADE
            else -> PreviewAdviceCode.AMBIENT_TOO_BRIGHT
        }
        return FilmPreviewEvaluation(
            presetEv100 = presetEv100,
            sceneDeltaEv = deltaEv,
            rating = rating,
            adviceCode = advice,
        )
    }

    private const val NORMAL_DELTA_EV = 1.0
    private const val CAUTION_DELTA_EV = 2.0
}
