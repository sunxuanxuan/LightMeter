package com.lightmeter.app.ui

import androidx.lifecycle.ViewModel
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.exposure.ExposurePair
import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.CameraMeteringPreset
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.FilmLatitudePreset
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.NormalizedPoint
import com.lightmeter.app.metering.MeteringResult
import com.lightmeter.app.settings.AppSettings
import com.lightmeter.app.settings.AppSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

enum class CameraPermissionState {
    UNKNOWN,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

data class MeteringUiState(
    val permissionState: CameraPermissionState = CameraPermissionState.UNKNOWN,
    val selectedIso: Int = 100,
    val exposureCompensation: Double = 0.0,
    val meteringRevision: Long = 0L,
    val meteringPreset: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val meteringMode: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val spotMeteringPoint: NormalizedPoint? = null,
    val spotAreaPercent: Int = 5,
    val centerAreaPercent: Int = 25,
    val centerWeightPercent: Int = 70,
    val cameraMeteringPreset: CameraMeteringPreset? = null,
    val frameFormat: FrameFormat = FrameFormat.FILM_135,
    val focalLengthMm: Double = 50.0,
    val cameraOptics: CameraOptics? = null,
    val ev100Metered: Double? = null,
    val measuredLuminance: Double? = null,
    val evTarget: Double? = null,
    val primaryExposure: ExposurePair? = null,
    val equivalentExposures: List<ExposurePair> = emptyList(),
    val apertureCandidates: List<String> = apertureStops.map(ApertureStop::label),
    val shutterCandidates: List<String> = shutterStops.map(ShutterStop::label),
    val selectedAperture: Double? = null,
    val calibrationOffset: Double = 0.0,
    val grayCardCalibrationCompleted: Boolean = false,
    val grayCardCalibrationPromptSeen: Boolean = false,
    val grayCardCalibrationSignature: String? = null,
    val exposureRiskEnabled: Boolean = true,
    val highlightLatitudeStops: Double = 4.0,
    val shadowLatitudeStops: Double = 3.0,
    val filmLatitudePreset: FilmLatitudePreset? = null,
    val isCameraReady: Boolean = false,
    val isFrozen: Boolean = false,
    val freezeRequestId: Int = 0,
    val errorMessage: String? = null,
)

class MeteringViewModel(
    private val settingsStore: AppSettingsStore = AppSettingsStore.None,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        restoreSettings(settingsStore.load()),
    )
    val state: StateFlow<MeteringUiState> = mutableState.asStateFlow()

    fun saveSettings(): Boolean {
        val saved = settingsStore.save(
            mutableState.value.toAppSettings().copy(
                themeStyle = settingsStore.load().themeStyle,
            ),
        )
        if (!saved) {
            mutableState.update { it.copy(errorMessage = "设置保存失败，请重试") }
        }
        return saved
    }

    fun updatePermission(state: CameraPermissionState) {
        mutableState.update {
            it.copy(
                permissionState = state,
                isCameraReady = if (state == CameraPermissionState.GRANTED) {
                    it.isCameraReady
                } else {
                    false
                },
                isFrozen = if (state == CameraPermissionState.GRANTED) {
                    it.isFrozen
                } else {
                    false
                },
            )
        }
    }

    fun onCameraReady() {
        mutableState.update { it.copy(isCameraReady = true, errorMessage = null) }
    }

    fun onCameraOpticsAvailable(optics: CameraOptics) {
        mutableState.update {
            if (it.cameraOptics == optics) return@update it
            it.copy(cameraOptics = optics).withoutMeteringResult()
        }
    }

    fun onCameraError(error: Throwable) {
        mutableState.update {
            it.copy(
                isCameraReady = false,
                errorMessage = error.message ?: "Camera initialization failed",
            )
        }
    }

    fun applyCalibrationOffset(offset: Double) {
        if (!offset.isFinite()) return
        mutableState.update {
            if (abs(it.calibrationOffset - offset) < 1e-9) return@update it
            it.copy(
                calibrationOffset = offset.coerceIn(
                    MIN_CALIBRATION_OFFSET,
                    MAX_CALIBRATION_OFFSET,
                ),
            ).withInvalidatedMetering()
        }
    }

    fun selectSpot(point: NormalizedPoint) {
        mutableState.update {
            it.copy(
                meteringMode = MeteringMode.SPOT,
                spotMeteringPoint = point,
            ).withoutMeteringResult()
        }
    }

    fun restoreMeteringPreset() {
        mutableState.update {
            it.copy(
                meteringMode = it.meteringPreset,
                spotMeteringPoint = null,
            ).withoutMeteringResult()
        }
    }

    fun selectMeteringPreset(mode: MeteringMode) {
        mutableState.update {
            it.copy(
                meteringPreset = mode,
                meteringMode = mode,
                spotMeteringPoint = null,
                cameraMeteringPreset = null,
            ).withoutMeteringResult()
        }
    }

    fun selectCameraMeteringPreset(preset: CameraMeteringPreset?) {
        mutableState.update {
            if (preset == null) {
                it.copy(cameraMeteringPreset = null)
            } else {
                it.copy(
                    meteringPreset = preset.meteringMode,
                    meteringMode = preset.meteringMode,
                    spotMeteringPoint = null,
                    spotAreaPercent = preset.spotAreaPercent,
                    centerAreaPercent = preset.centerAreaPercent,
                    centerWeightPercent = preset.centerWeightPercent,
                    focalLengthMm = preset.focalLengthMm ?: it.focalLengthMm,
                    cameraMeteringPreset = preset,
                ).withoutMeteringResult()
            }
        }
    }

    fun adjustSpotAreaPercent(delta: Int) {
        mutableState.update {
            val maximum = if (it.meteringPreset == MeteringMode.CENTER_AVERAGE) 20 else 10
            it.copy(
                spotAreaPercent = (it.spotAreaPercent + delta).coerceIn(1, maximum),
                cameraMeteringPreset = null,
            ).withoutMeteringResult()
        }
    }

    fun adjustCenterAreaPercent(delta: Int) {
        mutableState.update {
            it.copy(
                centerAreaPercent = (it.centerAreaPercent + delta).coerceIn(5, 80),
                cameraMeteringPreset = null,
            ).withoutMeteringResult()
        }
    }

    fun adjustCenterWeightPercent(delta: Int) {
        mutableState.update {
            it.copy(
                centerWeightPercent = (it.centerWeightPercent + delta).coerceIn(50, 95),
                cameraMeteringPreset = null,
            ).withoutMeteringResult()
        }
    }

    fun adjustHighlightLatitude(delta: Double) {
        mutableState.update {
            it.copy(
                highlightLatitudeStops = normalizeThirdStop(
                    it.highlightLatitudeStops + delta,
                    MIN_LATITUDE_STOPS,
                    MAX_LATITUDE_STOPS,
                ),
                filmLatitudePreset = null,
            )
        }
    }

    fun setExposureRiskEnabled(enabled: Boolean) {
        mutableState.update {
            it.copy(exposureRiskEnabled = enabled)
        }
    }

    fun selectFilmLatitudePreset(preset: FilmLatitudePreset?) {
        mutableState.update {
            if (preset == null) {
                it.copy(filmLatitudePreset = null)
            } else {
                it.copy(
                    highlightLatitudeStops = preset.highlightStops,
                    shadowLatitudeStops = preset.shadowStops,
                    filmLatitudePreset = preset,
                )
            }
        }
    }

    fun adjustShadowLatitude(delta: Double) {
        mutableState.update {
            it.copy(
                shadowLatitudeStops = normalizeThirdStop(
                    it.shadowLatitudeStops + delta,
                    MIN_LATITUDE_STOPS,
                    MAX_LATITUDE_STOPS,
                ),
                filmLatitudePreset = null,
            )
        }
    }

    fun onMeteringResult(result: MeteringResult) {
        mutableState.update {
            if (it.isFrozen || result.revision != it.meteringRevision) return@update it
            it.copy(
                ev100Metered = result.ev100,
                measuredLuminance = result.measuredLuminance,
            ).withRecommendation()
        }
    }

    fun freezePreview() {
        mutableState.update {
            if (!it.isCameraReady || it.ev100Metered == null || it.isFrozen) {
                return@update it
            }
            it.copy(
                isFrozen = true,
                freezeRequestId = it.freezeRequestId + 1,
                errorMessage = null,
            )
        }
    }

    fun onFrozenSnapshot(requestId: Int, snapshot: ExposureSnapshot) {
        mutableState.update {
            if (!it.isFrozen || it.freezeRequestId != requestId ||
                snapshot.revision != it.meteringRevision
            ) {
                return@update it
            }
            it.copy(
                ev100Metered = snapshot.meteredEv100,
            ).withRecommendation()
        }
    }

    fun resumeLivePreview() {
        mutableState.update {
            it.copy(
                isFrozen = false,
                errorMessage = null,
            )
        }
    }

    fun onFreezeCaptureFailed() {
        mutableState.update {
            it.copy(
                isFrozen = false,
                errorMessage = "无法定格当前预览，请重试",
            )
        }
    }

    fun selectIso(iso: Int) {
        mutableState.update {
            val normalizedIso = (
                iso.coerceIn(MIN_ISO, MAX_ISO) / ISO_STEP.toDouble()
                ).roundToInt() * ISO_STEP
            it.copy(selectedIso = normalizedIso).withRecommendation()
        }
    }

    fun selectFrameFormat(frameFormat: FrameFormat) {
        mutableState.update {
            if (it.frameFormat == frameFormat) return@update it
            it.copy(
                frameFormat = frameFormat,
                meteringMode = it.meteringPreset,
                spotMeteringPoint = null,
            ).withInvalidatedMetering()
        }
    }

    fun selectFocalLength(focalLengthMm: Double) {
        mutableState.update {
            val normalizedFocalLength = focalLengthMm.coerceIn(
                MIN_FOCAL_LENGTH_MM,
                MAX_FOCAL_LENGTH_MM,
            )
            if (normalizedFocalLength == it.focalLengthMm) return@update it
            it.copy(
                focalLengthMm = normalizedFocalLength,
                meteringMode = it.meteringPreset,
                spotMeteringPoint = null,
            ).withInvalidatedMetering()
        }
    }

    fun adjustExposureCompensation(delta: Double) {
        mutableState.update {
            it.copy(
                exposureCompensation = normalizeThirdStop(
                    it.exposureCompensation + delta,
                    MIN_EXPOSURE_COMPENSATION,
                    MAX_EXPOSURE_COMPENSATION,
                ),
            )
                .withRecommendation()
        }
    }

    fun selectExposureCompensation(value: Double) {
        mutableState.update {
            it.copy(
                exposureCompensation = normalizeThirdStop(
                    value,
                    MIN_EXPOSURE_COMPENSATION,
                    MAX_EXPOSURE_COMPENSATION,
                ),
            ).withRecommendation()
        }
    }

    fun stepAperture(delta: Int) {
        mutableState.update { state ->
            val targetEv = state.evTarget ?: return@update state
            val currentIndex = state.primaryExposure?.apertureLabel
                ?.let { label -> apertureStops.indexOfFirst { it.label == label } }
                ?.takeIf { it >= 0 }
                ?: return@update state
            val aperture = apertureStops[
                (currentIndex + delta).coerceIn(
                    0,
                    apertureStops.lastIndex,
                )
            ]
            val nextPair = closestPairForAperture(aperture, targetEv)
            state.copy(
                primaryExposure = nextPair,
                selectedAperture = nextPair.aperture,
            )
        }
    }

    fun stepShutter(delta: Int) {
        mutableState.update { state ->
            val targetEv = state.evTarget ?: return@update state
            val currentIndex = state.primaryExposure?.shutterLabel
                ?.let { label -> shutterStops.indexOfFirst { it.label == label } }
                ?.takeIf { it >= 0 }
                ?: return@update state
            val shutter = shutterStops[
                (currentIndex + delta).coerceIn(
                    0,
                    shutterStops.lastIndex,
                )
            ]
            val nextPair = closestPairForShutter(shutter, targetEv)
            state.copy(
                primaryExposure = nextPair,
                selectedAperture = nextPair.aperture,
            )
        }
    }

    companion object {
        private const val MIN_ISO = 100
        private const val MAX_ISO = 1600
        private const val ISO_STEP = 50
        private const val MIN_EXPOSURE_COMPENSATION = -3.0
        private const val MAX_EXPOSURE_COMPENSATION = 3.0
        private const val MIN_LATITUDE_STOPS = 1.0 / 3.0
        private const val MAX_LATITUDE_STOPS = 8.0
        const val EV_THIRD_STEP = 1.0 / 3.0

        val isoOptions = (MIN_ISO..MAX_ISO step ISO_STEP).toList()
        val exposureCompensationOptions = (-9..9).map {
            it * EV_THIRD_STEP
        }
        const val MIN_FOCAL_LENGTH_MM = 20.0
        const val MAX_FOCAL_LENGTH_MM = 150.0

        private fun restoreSettings(settings: AppSettings): MeteringUiState {
            val defaults = MeteringUiState()
            val normalizedIso = (
                settings.selectedIso.coerceIn(MIN_ISO, MAX_ISO) / ISO_STEP.toDouble()
                ).roundToInt() * ISO_STEP
            return defaults.copy(
                selectedIso = normalizedIso,
                exposureCompensation = normalizeThirdStop(
                    settings.exposureCompensation.takeIf(Double::isFinite)
                        ?: defaults.exposureCompensation,
                    MIN_EXPOSURE_COMPENSATION,
                    MAX_EXPOSURE_COMPENSATION,
                ),
                meteringPreset = settings.meteringPreset,
                meteringMode = settings.meteringPreset,
                spotAreaPercent = settings.spotAreaPercent.coerceIn(1, 20),
                centerAreaPercent = settings.centerAreaPercent.coerceIn(5, 80),
                centerWeightPercent = settings.centerWeightPercent.coerceIn(50, 95),
                cameraMeteringPreset = settings.cameraMeteringPreset,
                frameFormat = settings.frameFormat,
                focalLengthMm = settings.focalLengthMm
                    .takeIf(Double::isFinite)
                    ?.coerceIn(MIN_FOCAL_LENGTH_MM, MAX_FOCAL_LENGTH_MM)
                    ?: defaults.focalLengthMm,
                exposureRiskEnabled = settings.exposureRiskEnabled,
                highlightLatitudeStops = normalizeThirdStop(
                    settings.highlightLatitudeStops.takeIf(Double::isFinite)
                        ?: defaults.highlightLatitudeStops,
                    MIN_LATITUDE_STOPS,
                    MAX_LATITUDE_STOPS,
                ),
                shadowLatitudeStops = normalizeThirdStop(
                    settings.shadowLatitudeStops.takeIf(Double::isFinite)
                        ?: defaults.shadowLatitudeStops,
                    MIN_LATITUDE_STOPS,
                    MAX_LATITUDE_STOPS,
                ),
                filmLatitudePreset = settings.filmLatitudePreset,
                calibrationOffset = settings.calibrationOffset
                    .takeIf(Double::isFinite)
                    ?.coerceIn(MIN_CALIBRATION_OFFSET, MAX_CALIBRATION_OFFSET)
                    ?: defaults.calibrationOffset,
                grayCardCalibrationCompleted = settings.grayCardCalibrationCompleted,
                grayCardCalibrationPromptSeen = settings.grayCardCalibrationPromptSeen,
                grayCardCalibrationSignature = settings.grayCardCalibrationSignature,
            )
        }

        private const val MIN_CALIBRATION_OFFSET = -3.0
        private const val MAX_CALIBRATION_OFFSET = 3.0
    }
}

private fun MeteringUiState.toAppSettings() = AppSettings(
    selectedIso = selectedIso,
    exposureCompensation = exposureCompensation,
    meteringPreset = meteringPreset,
    spotAreaPercent = spotAreaPercent,
    centerAreaPercent = centerAreaPercent,
    centerWeightPercent = centerWeightPercent,
    cameraMeteringPreset = cameraMeteringPreset,
    frameFormat = frameFormat,
    focalLengthMm = focalLengthMm,
    exposureRiskEnabled = exposureRiskEnabled,
    highlightLatitudeStops = highlightLatitudeStops,
    shadowLatitudeStops = shadowLatitudeStops,
    filmLatitudePreset = filmLatitudePreset,
    calibrationOffset = calibrationOffset,
    grayCardCalibrationCompleted = grayCardCalibrationCompleted,
    grayCardCalibrationPromptSeen = grayCardCalibrationPromptSeen,
    grayCardCalibrationSignature = grayCardCalibrationSignature,
)

private fun normalizeThirdStop(
    value: Double,
    minimum: Double,
    maximum: Double,
): Double {
    return (value * 3.0).roundToInt().div(3.0).coerceIn(minimum, maximum)
}

private data class ApertureStop(
    val label: String,
    val value: Double,
)

private data class ShutterStop(
    val label: String,
    val seconds: Double,
)

private val apertureStops = listOf(
    ApertureStop("f/1", 1.0),
    ApertureStop("f/1.1", 1.1),
    ApertureStop("f/1.2", 1.2),
    ApertureStop("f/1.4", 1.4),
    ApertureStop("f/1.6", 1.6),
    ApertureStop("f/1.8", 1.8),
    ApertureStop("f/2", 2.0),
    ApertureStop("f/2.2", 2.2),
    ApertureStop("f/2.5", 2.5),
    ApertureStop("f/2.8", 2.8),
    ApertureStop("f/3.2", 3.2),
    ApertureStop("f/3.5", 3.5),
    ApertureStop("f/4", 4.0),
    ApertureStop("f/4.5", 4.5),
    ApertureStop("f/5", 5.0),
    ApertureStop("f/5.6", 5.6),
    ApertureStop("f/6.3", 6.3),
    ApertureStop("f/7.1", 7.1),
    ApertureStop("f/8", 8.0),
    ApertureStop("f/9", 9.0),
    ApertureStop("f/10", 10.0),
    ApertureStop("f/11", 11.0),
    ApertureStop("f/13", 13.0),
    ApertureStop("f/14", 14.0),
    ApertureStop("f/16", 16.0),
    ApertureStop("f/18", 18.0),
    ApertureStop("f/20", 20.0),
    ApertureStop("f/22", 22.0),
)

private val shutterStops = listOf(
    ShutterStop("1/2000", 1.0 / 2000.0),
    ShutterStop("1/1000", 1.0 / 1000.0),
    ShutterStop("1/500", 1.0 / 500.0),
    ShutterStop("1/250", 1.0 / 250.0),
    ShutterStop("1/125", 1.0 / 125.0),
    ShutterStop("1/60", 1.0 / 60.0),
    ShutterStop("1/30", 1.0 / 30.0),
    ShutterStop("1/15", 1.0 / 15.0),
    ShutterStop("1/8", 1.0 / 8.0),
    ShutterStop("1/4", 1.0 / 4.0),
    ShutterStop("1/2", 1.0 / 2.0),
    ShutterStop("1s", 1.0),
    ShutterStop("2s", 2.0),
    ShutterStop("4s", 4.0),
    ShutterStop("8s", 8.0),
)

private val commonAperturePriority = listOf(5.6, 8.0, 4.0, 11.0, 2.8, 16.0)

private fun MeteringUiState.withoutMeteringResult(): MeteringUiState {
    return withInvalidatedMetering().copy(
        ev100Metered = null,
        measuredLuminance = null,
        evTarget = null,
        primaryExposure = null,
        equivalentExposures = emptyList(),
    )
}

private fun MeteringUiState.withInvalidatedMetering(): MeteringUiState {
    return copy(meteringRevision = meteringRevision + 1L)
}

private fun MeteringUiState.withRecommendation(): MeteringUiState {
    val ev100 = ev100Metered ?: return this
    val target = targetEv(ev100, selectedIso, exposureCompensation)
    val pairs = generateExposurePairs(target)
    val recommended = selectPrimaryPair(pairs, safeShutterSeconds(focalLengthMm))
    val selected = selectedAperture?.let { aperture ->
        pairs.minByOrNull { abs(it.aperture - aperture) }
    } ?: recommended

    return copy(
        evTarget = target,
        primaryExposure = selected,
        equivalentExposures = pairs,
    )
}

private fun targetEv(
    ev100: Double,
    iso: Int,
    exposureCompensation: Double,
): Double {
    return ev100 + log2(iso / 100.0) - exposureCompensation
}

private fun generateExposurePairs(targetEv: Double): List<ExposurePair> {
    val nearestByAperture = apertureStops.map { aperture ->
        closestPairForAperture(aperture, targetEv)
    }
    val withinTolerance = nearestByAperture.filter {
        it.error <= MAX_EQUIVALENT_EXPOSURE_ERROR
    }
    val candidates = withinTolerance.ifEmpty {
        val minimumError = nearestByAperture.minOf(ExposurePair::error)
        nearestByAperture.filter {
            abs(it.error - minimumError) < EXPOSURE_ERROR_EPSILON
        }
    }
    return candidates
        .groupBy(ExposurePair::shutterLabel)
        .values
        .map { sameShutter -> sameShutter.minBy(ExposurePair::error) }
        .sortedBy(ExposurePair::shutterSeconds)
}

private fun closestPairForAperture(
    aperture: ApertureStop,
    targetEv: Double,
): ExposurePair {
    return shutterStops
        .map { shutter -> exposurePair(aperture, shutter, targetEv) }
        .minBy { it.error }
}

private fun closestPairForShutter(
    shutter: ShutterStop,
    targetEv: Double,
): ExposurePair {
    return apertureStops
        .map { aperture -> exposurePair(aperture, shutter, targetEv) }
        .minBy { it.error }
}

private fun exposurePair(
    aperture: ApertureStop,
    shutter: ShutterStop,
    targetEv: Double,
): ExposurePair {
    val pairEv = log2(aperture.value * aperture.value / shutter.seconds)
    return ExposurePair(
        apertureLabel = aperture.label,
        aperture = aperture.value,
        shutterLabel = shutter.label,
        shutterSeconds = shutter.seconds,
        ev = pairEv,
        error = abs(pairEv - targetEv),
    )
}

private fun selectPrimaryPair(
    pairs: List<ExposurePair>,
    safeShutterSeconds: Double,
): ExposurePair? {
    val safePairs = pairs.filter { it.shutterSeconds <= safeShutterSeconds }
    if (safePairs.isEmpty()) {
        return pairs.minWithOrNull(
            compareBy<ExposurePair>(ExposurePair::error)
                .thenBy(ExposurePair::shutterSeconds),
        )
    }

    return safePairs.minWithOrNull(
        compareBy<ExposurePair> {
            commonAperturePriority.indexOf(it.aperture).takeIf { index -> index >= 0 }
                ?: Int.MAX_VALUE
        }
            .thenBy { it.error }
            .thenBy { it.shutterSeconds },
    )
}

private fun safeShutterSeconds(focalLengthMm: Double): Double {
    return when {
        focalLengthMm <= 35.0 -> 1.0 / 30.0
        focalLengthMm <= 50.0 -> 1.0 / 60.0
        focalLengthMm <= 90.0 -> 1.0 / 125.0
        else -> 1.0 / 250.0
    }
}

private const val MAX_EQUIVALENT_EXPOSURE_ERROR = 1.0 / 6.0
private const val EXPOSURE_ERROR_EPSILON = 1e-9
