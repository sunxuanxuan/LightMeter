package com.lightmeter.app.metering

import kotlin.math.abs

data class GrayCardCalibrationEstimate(
    val offset: Double,
    val measuredEv100: Double,
    val spreadStops: Double,
)

sealed interface GrayCardCalibrationResult {
    data class Success(val estimate: GrayCardCalibrationEstimate) :
        GrayCardCalibrationResult

    data class Failure(val reason: String) : GrayCardCalibrationResult
}

object GrayCardCalibration {
    const val REQUIRED_SAMPLE_COUNT = 12
    const val WARMUP_SAMPLE_COUNT = 5

    fun estimate(
        referenceEv100: Double,
        measuredEv100Samples: List<Double>,
    ): GrayCardCalibrationResult {
        if (!referenceEv100.isFinite() || referenceEv100 !in MIN_REFERENCE_EV..MAX_REFERENCE_EV) {
            return GrayCardCalibrationResult.Failure("参考 EV100 必须在 -6.0 到 24.0 之间")
        }
        val samples = measuredEv100Samples.filter(Double::isFinite)
        if (samples.size < REQUIRED_SAMPLE_COUNT) {
            return GrayCardCalibrationResult.Failure("有效样本不足，请重新采集")
        }

        val sorted = samples.sorted()
        val measuredMedian = median(sorted)
        val lower = percentile(sorted, 0.1)
        val upper = percentile(sorted, 0.9)
        val spread = upper - lower
        if (spread > MAX_STABLE_SPREAD_STOPS) {
            return GrayCardCalibrationResult.Failure("测光波动过大，请保持手机和灰卡稳定")
        }

        val offset = referenceEv100 - measuredMedian
        if (abs(offset) > MAX_ABSOLUTE_OFFSET_STOPS) {
            return GrayCardCalibrationResult.Failure(
                "偏差超过可校验范围，请确认参考 EV100 和灰卡照明",
            )
        }
        return GrayCardCalibrationResult.Success(
            GrayCardCalibrationEstimate(
                offset = offset,
                measuredEv100 = measuredMedian,
                spreadStops = spread,
            ),
        )
    }

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        val index = ((sorted.lastIndex) * fraction).toInt()
        return sorted[index.coerceIn(sorted.indices)]
    }

    private const val MIN_REFERENCE_EV = -6.0
    private const val MAX_REFERENCE_EV = 24.0
    private const val MAX_STABLE_SPREAD_STOPS = 0.35
    private const val MAX_ABSOLUTE_OFFSET_STOPS = 3.0
}
