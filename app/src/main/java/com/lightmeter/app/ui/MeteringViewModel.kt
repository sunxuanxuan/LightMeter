package com.lightmeter.app.ui

import androidx.lifecycle.ViewModel
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.exposure.ExposurePair
import com.lightmeter.app.exposure.FramePreset
import com.lightmeter.app.metering.CameraMeteringPreset
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.NormalizedPoint
import com.lightmeter.app.metering.MeteringResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.log2

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
    val meteringPreset: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val meteringMode: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val spotMeteringPoint: NormalizedPoint? = null,
    val spotAreaPercent: Int = 5,
    val centerAreaPercent: Int = 25,
    val centerWeightPercent: Int = 70,
    val cameraMeteringPreset: CameraMeteringPreset? = null,
    val framePreset: FramePreset = FramePreset.FILM_135_50MM,
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
    val isCameraReady: Boolean = false,
    val isFrozen: Boolean = false,
    val freezeRequestId: Int = 0,
    val errorMessage: String? = null,
)

class MeteringViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(MeteringUiState())
    val state: StateFlow<MeteringUiState> = mutableState.asStateFlow()

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
        mutableState.update { it.copy(cameraOptics = optics) }
    }

    fun onCameraError(error: Throwable) {
        mutableState.update {
            it.copy(
                isCameraReady = false,
                errorMessage = error.message ?: "Camera initialization failed",
            )
        }
    }

    fun selectSpot(point: NormalizedPoint) {
        mutableState.update {
            it.copy(
                meteringMode = MeteringMode.SPOT,
                spotMeteringPoint = point,
            )
        }
    }

    fun restoreMeteringPreset() {
        mutableState.update {
            it.copy(
                meteringMode = it.meteringPreset,
                spotMeteringPoint = null,
            )
        }
    }

    fun selectMeteringPreset(mode: MeteringMode) {
        mutableState.update {
            it.copy(
                meteringPreset = mode,
                meteringMode = mode,
                spotMeteringPoint = null,
                cameraMeteringPreset = null,
            )
        }
    }

    fun selectCameraMeteringPreset(preset: CameraMeteringPreset?) {
        mutableState.update {
            if (preset == null) {
                it.copy(cameraMeteringPreset = null)
            } else {
                it.copy(
                    meteringPreset = MeteringMode.CENTER_WEIGHTED,
                    meteringMode = MeteringMode.CENTER_WEIGHTED,
                    spotMeteringPoint = null,
                    centerAreaPercent = preset.centerAreaPercent,
                    centerWeightPercent = preset.centerWeightPercent,
                    cameraMeteringPreset = preset,
                )
            }
        }
    }

    fun adjustSpotAreaPercent(delta: Int) {
        mutableState.update {
            it.copy(
                spotAreaPercent = (it.spotAreaPercent + delta).coerceIn(1, 10),
            )
        }
    }

    fun adjustCenterAreaPercent(delta: Int) {
        mutableState.update {
            it.copy(
                centerAreaPercent = (it.centerAreaPercent + delta).coerceIn(5, 80),
                cameraMeteringPreset = null,
            )
        }
    }

    fun adjustCenterWeightPercent(delta: Int) {
        mutableState.update {
            it.copy(
                centerWeightPercent = (it.centerWeightPercent + delta).coerceIn(50, 95),
                cameraMeteringPreset = null,
            )
        }
    }

    fun onMeteringResult(result: MeteringResult) {
        mutableState.update {
            if (it.isFrozen) return@update it
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
            it.copy(selectedIso = iso).withRecommendation()
        }
    }

    fun selectFramePreset(framePreset: FramePreset) {
        mutableState.update {
            it.copy(framePreset = framePreset).withRecommendation()
        }
    }

    fun adjustExposureCompensation(delta: Double) {
        mutableState.update {
            it.copy(
                exposureCompensation = (it.exposureCompensation + delta)
                    .coerceIn(-3.0, 3.0),
            )
                .withRecommendation()
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
        val isoOptions = listOf(50, 100, 200, 400, 800, 1600)
    }
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

private fun MeteringUiState.withRecommendation(): MeteringUiState {
    val ev100 = ev100Metered ?: return this
    val target = targetEv(ev100, selectedIso, exposureCompensation)
    val pairs = generateExposurePairs(target)
    val recommended = selectPrimaryPair(pairs, framePreset)
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
    return apertureStops.map { aperture ->
        closestPairForAperture(aperture, targetEv)
    }
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
    framePreset: FramePreset,
): ExposurePair? {
    val safePairs = pairs.filter { it.shutterSeconds <= framePreset.safeShutterSeconds }
    val candidates = safePairs.ifEmpty { pairs }

    return candidates.minWithOrNull(
        compareBy<ExposurePair> {
            commonAperturePriority.indexOf(it.aperture).takeIf { index -> index >= 0 }
                ?: Int.MAX_VALUE
        }
            .thenBy { it.error }
            .thenBy { it.shutterSeconds },
    )
}
