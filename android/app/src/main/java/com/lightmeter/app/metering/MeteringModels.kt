package com.lightmeter.app.metering

enum class MeteringMode {
    SPOT,
    CENTER_AVERAGE,
    CENTER_CROP_AVERAGE,
    CENTER_WEIGHTED,
    AVERAGE,
}

enum class CameraMeteringPreset(
    val displayName: String,
    val meteringMode: MeteringMode,
    val meteringAreaPercent: Int?,
    val centerAreaPercent: Int,
    val centerWeightPercent: Int,
) {
    CANON_NEW_F1(
        displayName = "佳能 New F-1",
        meteringMode = MeteringMode.CENTER_WEIGHTED,
        meteringAreaPercent = null,
        centerAreaPercent = 60,
        centerWeightPercent = 70,
    ),
    CANON_AL1(
        displayName = "佳能 AL-1",
        meteringMode = MeteringMode.CENTER_WEIGHTED,
        meteringAreaPercent = null,
        centerAreaPercent = 40,
        centerWeightPercent = 65,
    ),
    OLYMPUS_35_SP_AVERAGE(
        displayName = "奥林巴斯 35 SP",
        meteringMode = MeteringMode.CENTER_AVERAGE,
        meteringAreaPercent = 20,
        centerAreaPercent = 20,
        centerWeightPercent = 95,
    ),
    OLYMPUS_35_SP_SPOT(
        displayName = "奥林巴斯 35 SP",
        meteringMode = MeteringMode.SPOT,
        meteringAreaPercent = 2,
        centerAreaPercent = 20,
        centerWeightPercent = 95,
    ),
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

data class NormalizedMeteringRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0)
        require(top in 0.0..1.0)
        require(right in 0.0..1.0)
        require(bottom in 0.0..1.0)
        require(left < right)
        require(top < bottom)
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0

    fun contains(x: Double, y: Double): Boolean {
        return x in left..right && y in top..bottom
    }

    companion object {
        val Full = NormalizedMeteringRect(0.0, 0.0, 1.0, 1.0)
    }
}

data class MeteringConfig(
    val mode: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val spotPoint: NormalizedPoint? = null,
    val spotAreaPercent: Int = 5,
    val centerAverageAreaPercent: Int = 20,
    val centerCropPercent: Int = 60,
    val centerAreaPercent: Int = 25,
    val centerWeightPercent: Int = 70,
    val viewfinderRect: NormalizedMeteringRect = NormalizedMeteringRect.Full,
    val previewAspectRatio: Double = 1.0,
    val targetZoomRatio: Double = 1.0,
    val isZoomReady: Boolean = true,
    val revision: Long = 0L,
    val calibrationOffset: Double = 0.0,
) {
    init {
        require(spotAreaPercent in 1..10)
        require(centerAverageAreaPercent in 1..20)
        require(centerCropPercent in 10..100)
        require(centerAreaPercent in 5..80)
        require(centerWeightPercent in 50..95)
        require(previewAspectRatio > 0.0)
        require(targetZoomRatio > 0.0)
    }
}

data class MeteringResult(
    val ev100: Double,
    val measuredLuminance: Double,
    val timestampNs: Long,
    val revision: Long = 0L,
)

data class ExposureSnapshot(
    val exposureMap: ExposureMap,
    val meteredEv100: Double,
    val timestampNs: Long,
    val revision: Long,
) {
    init {
        require(meteredEv100.isFinite())
        require(exposureMap.timestampNs == timestampNs)
        require(exposureMap.revision == revision)
    }
}
