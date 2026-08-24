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

        val shoulderStartStops = minOf(
            highlightLatitudeStops,
            DISPLAY_SHOULDER_START_STOPS,
        )
        return when {
            deltaEv < -shadowLatitudeStops -> {
                val progress = (
                    (-deltaEv - shadowLatitudeStops) / OUTSIDE_EXTENSION_STOPS
                    ).coerceIn(0.0, 1.0)
                interpolate(
                    from = linearLuminance(-shadowLatitudeStops),
                    to = 0.0,
                    progress = progress,
                )
            }

            deltaEv > shoulderStartStops -> {
                val progress = (
                    (deltaEv - shoulderStartStops) /
                        (highlightLatitudeStops + OUTSIDE_EXTENSION_STOPS - shoulderStartStops)
                    ).coerceIn(0.0, 1.0)
                interpolate(
                    from = linearLuminance(shoulderStartStops),
                    to = 1.0,
                    progress = progress,
                )
            }

            else -> {
                linearLuminance(deltaEv)
            }
        }.let {
            if (abs(it) < LUMINANCE_EPSILON) 0.0 else it.coerceIn(0.0, 1.0)
        }
    }

    private fun linearLuminance(deltaEv: Double): Double {
        return MIDDLE_GRAY_LUMINANCE * 2.0.pow(deltaEv)
    }

    private fun interpolate(from: Double, to: Double, progress: Double): Double {
        return from + (to - from) * smoothstep(progress)
    }

    private fun smoothstep(value: Double): Double {
        val normalized = value.coerceIn(0.0, 1.0)
        return normalized * normalized * (3.0 - 2.0 * normalized)
    }

    private const val MIDDLE_GRAY_LUMINANCE = 0.18
    private const val DISPLAY_SHOULDER_START_STOPS = 2.0
    private const val OUTSIDE_EXTENSION_STOPS = 2.0
    private const val LUMINANCE_EPSILON = 1e-9
}
