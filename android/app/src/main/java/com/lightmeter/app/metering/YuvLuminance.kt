package com.lightmeter.app.metering

import android.hardware.DataSpace
import kotlin.math.max
import kotlin.math.pow

internal enum class YuvLuminanceRange(
    val blackLevel: Int,
    val whiteLevel: Int,
) {
    FULL(0, 255),
    LIMITED(16, 235),
}

internal object YuvLuminance {
    fun rangeForDataSpace(dataSpace: Int): YuvLuminanceRange? {
        return when (DataSpace.getRange(dataSpace)) {
            DataSpace.RANGE_FULL -> YuvLuminanceRange.FULL
            DataSpace.RANGE_LIMITED -> YuvLuminanceRange.LIMITED
            else -> null
        }
    }

    fun normalized(
        raw: Int,
        range: YuvLuminanceRange = YuvLuminanceRange.LIMITED,
    ): Double {
        return (
            (raw.coerceIn(range.blackLevel, range.whiteLevel) - range.blackLevel) /
                (range.whiteLevel - range.blackLevel).toDouble()
            )
            .coerceIn(0.0, 1.0)
    }

    fun linear(
        raw: Int,
        range: YuvLuminanceRange = YuvLuminanceRange.LIMITED,
    ): Double {
        return max(normalized(raw, range).pow(GAMMA), MIN_LINEAR_LUMINANCE)
    }

    fun isHighlightClipped(
        raw: Int,
        range: YuvLuminanceRange = YuvLuminanceRange.LIMITED,
    ): Boolean = raw >= range.whiteLevel

    private const val GAMMA = 2.2
    private const val MIN_LINEAR_LUMINANCE = 1e-6
}
