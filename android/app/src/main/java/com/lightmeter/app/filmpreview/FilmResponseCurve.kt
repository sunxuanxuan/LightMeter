package com.lightmeter.app.filmpreview

import kotlin.math.abs

internal object FilmResponseCurve {
    fun targetLuminance(
        deltaEv: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): Double {
        require(deltaEv.isFinite())
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)

        return when {
            deltaEv < -shadowLatitudeStops -> {
                val progress = (
                    (-deltaEv - shadowLatitudeStops) / OUTSIDE_EXTENSION_STOPS
                    ).coerceIn(0.0, 1.0)
                SHADOW_EDGE_LUMINANCE * (1.0 - smoothstep(progress))
            }

            deltaEv < 0.0 -> {
                val progress = (
                    (deltaEv + shadowLatitudeStops) / shadowLatitudeStops
                    ).coerceIn(0.0, 1.0)
                interpolate(
                    from = SHADOW_EDGE_LUMINANCE,
                    to = MIDDLE_GRAY_LUMINANCE,
                    progress = progress,
                )
            }

            deltaEv <= highlightLatitudeStops -> {
                val progress = (deltaEv / highlightLatitudeStops).coerceIn(0.0, 1.0)
                interpolate(
                    from = MIDDLE_GRAY_LUMINANCE,
                    to = HIGHLIGHT_EDGE_LUMINANCE,
                    progress = progress,
                )
            }

            else -> {
                val progress = (
                    (deltaEv - highlightLatitudeStops) / OUTSIDE_EXTENSION_STOPS
                    ).coerceIn(0.0, 1.0)
                interpolate(
                    from = HIGHLIGHT_EDGE_LUMINANCE,
                    to = 1.0,
                    progress = progress,
                )
            }
        }.let {
            if (abs(it) < LUMINANCE_EPSILON) 0.0 else it.coerceIn(0.0, 1.0)
        }
    }

    private fun interpolate(from: Double, to: Double, progress: Double): Double {
        return from + (to - from) * smoothstep(progress)
    }

    private fun smoothstep(value: Double): Double {
        val normalized = value.coerceIn(0.0, 1.0)
        return normalized * normalized * (3.0 - 2.0 * normalized)
    }

    private const val SHADOW_EDGE_LUMINANCE = 0.02
    private const val MIDDLE_GRAY_LUMINANCE = 0.18
    private const val HIGHLIGHT_EDGE_LUMINANCE = 0.98
    private const val OUTSIDE_EXTENSION_STOPS = 2.0
    private const val LUMINANCE_EPSILON = 1e-9
}
