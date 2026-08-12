package com.lightmeter.app.ui

import androidx.lifecycle.ViewModel
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.exposure.ExposurePair
import com.lightmeter.app.exposure.FramePreset
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
    val meteringMode: MeteringMode = MeteringMode.AVERAGE,
    val spotMeteringPoint: NormalizedPoint? = null,
    val framePreset: FramePreset = FramePreset.FILM_135_50MM,
    val cameraOptics: CameraOptics? = null,
    val ev100Metered: Double? = null,
    val measuredLuminance: Double? = null,
    val evTarget: Double? = null,
    val primaryExposure: ExposurePair? = null,
    val equivalentExposures: List<ExposurePair> = emptyList(),
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

    fun useAverageMetering() {
        mutableState.update {
            it.copy(
                meteringMode = MeteringMode.AVERAGE,
                spotMeteringPoint = null,
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

    fun stepExposurePair(delta: Int) {
        mutableState.update { state ->
            val currentIndex = state.primaryExposure
                ?.let(state.equivalentExposures::indexOf)
                ?.takeIf { it >= 0 }
                ?: return@update state
            val nextPair = state.equivalentExposures[
                (currentIndex + delta).coerceIn(
                    0,
                    state.equivalentExposures.lastIndex,
                )
            ]
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
    ShutterStop("30s", 30.0),
    ShutterStop("25s", 25.0),
    ShutterStop("20s", 20.0),
    ShutterStop("15s", 15.0),
    ShutterStop("13s", 13.0),
    ShutterStop("10s", 10.0),
    ShutterStop("8s", 8.0),
    ShutterStop("6s", 6.0),
    ShutterStop("5s", 5.0),
    ShutterStop("4s", 4.0),
    ShutterStop("3.2s", 3.2),
    ShutterStop("2.5s", 2.5),
    ShutterStop("2s", 2.0),
    ShutterStop("1.6s", 1.6),
    ShutterStop("1.3s", 1.3),
    ShutterStop("1s", 1.0),
    ShutterStop("1/1.3", 1.0 / 1.3),
    ShutterStop("1/1.6", 1.0 / 1.6),
    ShutterStop("1/2", 1.0 / 2.0),
    ShutterStop("1/2.5", 1.0 / 2.5),
    ShutterStop("1/3", 1.0 / 3.0),
    ShutterStop("1/4", 1.0 / 4.0),
    ShutterStop("1/5", 1.0 / 5.0),
    ShutterStop("1/6", 1.0 / 6.0),
    ShutterStop("1/8", 1.0 / 8.0),
    ShutterStop("1/10", 1.0 / 10.0),
    ShutterStop("1/13", 1.0 / 13.0),
    ShutterStop("1/15", 1.0 / 15.0),
    ShutterStop("1/20", 1.0 / 20.0),
    ShutterStop("1/25", 1.0 / 25.0),
    ShutterStop("1/30", 1.0 / 30.0),
    ShutterStop("1/40", 1.0 / 40.0),
    ShutterStop("1/50", 1.0 / 50.0),
    ShutterStop("1/60", 1.0 / 60.0),
    ShutterStop("1/80", 1.0 / 80.0),
    ShutterStop("1/100", 1.0 / 100.0),
    ShutterStop("1/125", 1.0 / 125.0),
    ShutterStop("1/160", 1.0 / 160.0),
    ShutterStop("1/200", 1.0 / 200.0),
    ShutterStop("1/250", 1.0 / 250.0),
    ShutterStop("1/320", 1.0 / 320.0),
    ShutterStop("1/400", 1.0 / 400.0),
    ShutterStop("1/500", 1.0 / 500.0),
    ShutterStop("1/640", 1.0 / 640.0),
    ShutterStop("1/800", 1.0 / 800.0),
    ShutterStop("1/4000", 1.0 / 4000.0),
    ShutterStop("1/3200", 1.0 / 3200.0),
    ShutterStop("1/2500", 1.0 / 2500.0),
    ShutterStop("1/2000", 1.0 / 2000.0),
    ShutterStop("1/1600", 1.0 / 1600.0),
    ShutterStop("1/1250", 1.0 / 1250.0),
    ShutterStop("1/1000", 1.0 / 1000.0),
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
    val closestByAperture = apertureStops.mapNotNull { aperture ->
        shutterStops
            .map { shutter ->
                val pairEv = log2(aperture.value * aperture.value / shutter.seconds)
                ExposurePair(
                    apertureLabel = aperture.label,
                    aperture = aperture.value,
                    shutterLabel = shutter.label,
                    shutterSeconds = shutter.seconds,
                    ev = pairEv,
                    error = abs(pairEv - targetEv),
                )
            }
            .minByOrNull { it.error }
    }
    val withinTolerance = closestByAperture.filter { it.error <= 1.0 / 6.0 }
    val candidates = withinTolerance.ifEmpty { closestByAperture }
    val seenApertures = mutableSetOf<String>()
    val seenShutters = mutableSetOf<String>()
    val uniquePairs = candidates
        .sortedBy { it.error }
        .filter { pair ->
            if (pair.apertureLabel in seenApertures || pair.shutterLabel in seenShutters) {
                false
            } else {
                seenApertures += pair.apertureLabel
                seenShutters += pair.shutterLabel
                true
            }
        }

    return uniquePairs
        .let { if (withinTolerance.isEmpty()) it.take(8) else it }
        .sortedBy { it.shutterSeconds }
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
