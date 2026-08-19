package com.lightmeter.app.filmpreview

import kotlin.math.max

internal object FilmGrainModel {
    fun intensity(baseGrainIntensity: Double, deltaEv: Double): Double {
        require(baseGrainIntensity in 0.0..MAX_BASE_INTENSITY)
        require(deltaEv.isFinite())

        val underexposureStops = max(-deltaEv, 0.0)
        return (
            baseGrainIntensity +
                underexposureStops * UNDEREXPOSURE_GRAIN_PER_STOP
            ).coerceAtMost(MAX_GRAIN_INTENSITY)
    }

    fun baseIntensityForIso(iso: Int): Double {
        require(iso > 0)
        return (BASE_INTENSITY_AT_ISO_400 * iso / 400.0)
            .coerceIn(MIN_BASE_INTENSITY, MAX_BASE_INTENSITY)
    }

    fun noise(x: Int, y: Int): Double {
        var hash = x * 0x1f1f1f1f + y * 0x45d9f3b
        hash = hash xor (hash ushr 16)
        hash *= 0x45d9f3b
        hash = hash xor (hash ushr 16)
        return (hash and 0x00ff_ffff) / 8_388_607.5 - 1.0
    }

    const val MAX_BASE_INTENSITY = 0.1
    const val MAX_GRAIN_INTENSITY = 0.065
    private const val MIN_BASE_INTENSITY = 0.004
    private const val BASE_INTENSITY_AT_ISO_400 = 0.012
    private const val UNDEREXPOSURE_GRAIN_PER_STOP = 0.012
}
