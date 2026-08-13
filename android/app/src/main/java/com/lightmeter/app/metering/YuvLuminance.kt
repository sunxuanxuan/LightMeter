package com.lightmeter.app.metering

import kotlin.math.max
import kotlin.math.pow

internal object YuvLuminance {
    fun normalized(raw: Int): Double {
        return ((raw.coerceIn(BLACK_LEVEL, WHITE_LEVEL) - BLACK_LEVEL) / RANGE)
            .coerceIn(0.0, 1.0)
    }

    fun linear(raw: Int): Double {
        return max(normalized(raw).pow(GAMMA), MIN_LINEAR_LUMINANCE)
    }

    fun isHighlightClipped(raw: Int): Boolean = raw >= WHITE_LEVEL

    private const val BLACK_LEVEL = 16
    private const val WHITE_LEVEL = 235
    private const val RANGE = (WHITE_LEVEL - BLACK_LEVEL).toDouble()
    private const val GAMMA = 2.2
    private const val MIN_LINEAR_LUMINANCE = 1e-6
}
