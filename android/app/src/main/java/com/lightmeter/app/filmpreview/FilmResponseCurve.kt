package com.lightmeter.app.filmpreview

import kotlin.math.abs
import kotlin.math.pow

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
                interpolate(
                    from = displayLuminance(-shadowLatitudeStops),
                    to = 0.0,
                    progress = progress,
                )
            }

            deltaEv > highlightLatitudeStops -> {
                val progress = (
                    (deltaEv - highlightLatitudeStops) / OUTSIDE_EXTENSION_STOPS
                    ).coerceIn(0.0, 1.0)
                interpolate(
                    from = displayLuminance(highlightLatitudeStops),
                    to = 1.0,
                    progress = progress,
                )
            }

            else -> {
                displayLuminance(deltaEv)
            }
        }.let {
            if (abs(it) < LUMINANCE_EPSILON) 0.0 else it.coerceIn(0.0, 1.0)
        }
    }

    private fun displayLuminance(deltaEv: Double): Double {
        return MIDDLE_GRAY_LUMINANCE * 2.0.pow(
            deltaEv * DISPLAY_EXPOSURE_SCALE,
        )
    }

    private fun interpolate(from: Double, to: Double, progress: Double): Double {
        return from + (to - from) * smoothstep(progress)
    }

    private fun smoothstep(value: Double): Double {
        val normalized = value.coerceIn(0.0, 1.0)
        return normalized * normalized * (3.0 - 2.0 * normalized)
    }

    private const val MIDDLE_GRAY_LUMINANCE = 0.18
    private const val DISPLAY_EXPOSURE_SCALE = 0.5
    private const val OUTSIDE_EXTENSION_STOPS = 2.0
    private const val LUMINANCE_EPSILON = 1e-9
}
