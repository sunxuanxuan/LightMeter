package com.lightmeter.app.metering

enum class MeteringMode {
    AVERAGE,
    SPOT,
}

data class NormalizedPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x in 0.0..1.0) { "x must be normalized" }
        require(y in 0.0..1.0) { "y must be normalized" }
    }
}

data class MeteringConfig(
    val mode: MeteringMode = MeteringMode.AVERAGE,
    val spotPoint: NormalizedPoint? = null,
    val calibrationOffset: Double = 0.0,
)

data class MeteringResult(
    val ev100: Double,
    val measuredLuminance: Double,
    val timestampNs: Long,
)
