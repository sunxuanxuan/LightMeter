package com.lightmeter.app.filmpreview

import com.lightmeter.app.metering.ExposureMap
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.MeteringResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmPreviewEngineTest {
    private val funSaver = requireNotNull(
        BuiltInDisposableCameraRepository.find("kodak-funsaver-800"),
    )
    private val quickSnap = requireNotNull(
        BuiltInDisposableCameraRepository.find("fujifilm-quicksnap-flash-400"),
    )

    @Test
    fun fixedCameraParametersProduceExpectedReferenceEv100() {
        val reference = FilmPreviewEngine.presetEv100(quickSnap)

        assertEquals(11.7731, reference, 0.0001)
    }

    @Test
    fun pixelDeltaUsesPresetReferenceInsteadOfMeteredSceneReference() {
        val reference = FilmPreviewEngine.presetEv100(quickSnap)

        assertEquals(
            2.0,
            FilmPreviewEngine.pixelDeltaEv(reference + 2.0, quickSnap),
            0.0001,
        )
        assertEquals(
            -2.0,
            FilmPreviewEngine.pixelDeltaEv(reference - 2.0, quickSnap),
            0.0001,
        )
    }

    @Test
    fun missingMeteringResultIsUnavailable() {
        val evaluation = FilmPreviewEngine.evaluate(null, quickSnap)

        assertNull(evaluation.sceneDeltaEv)
        assertEquals(PreviewSceneRating.UNAVAILABLE, evaluation.rating)
        assertEquals(PreviewAdviceCode.UNAVAILABLE, evaluation.adviceCode)
    }

    @Test
    fun darkSceneRecommendsFlashForFlashPreset() {
        val reference = FilmPreviewEngine.presetEv100(quickSnap)
        val evaluation = FilmPreviewEngine.evaluate(reference - 4.0, quickSnap)

        assertEquals(PreviewSceneRating.POOR, evaluation.rating)
        assertEquals(PreviewAdviceCode.USE_FLASH, evaluation.adviceCode)
    }

    @Test
    fun latitudeBoundaryIsIncludedInRiskAdvice() {
        val reference = FilmPreviewEngine.presetEv100(quickSnap)
        val evaluation = FilmPreviewEngine.evaluate(
            reference - quickSnap.film.shadowLatitudeStops,
            quickSnap,
        )

        assertEquals(PreviewSceneRating.CAUTION, evaluation.rating)
        assertEquals(PreviewAdviceCode.USE_FLASH, evaluation.adviceCode)
    }

    @Test
    fun builtInPresetIdsAndVersionsAreUnique() {
        val presets = BuiltInDisposableCameraRepository.presets()
        val keys = presets.map { it.id to it.presetVersion }

        assertEquals(4, presets.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(presets.all { it.presetVersion > 0 })
    }

    @Test
    fun builtInPresetParametersMatchDisposableCameraReference() {
        val presets = BuiltInDisposableCameraRepository.presets().associateBy { it.id }

        assertCameraParameters(
            requireNotNull(presets["kodak-funsaver-800"]),
            iso = 800,
            aperture = 10.0,
            focalLengthMm = 31.0,
            shutterSeconds = 1.0 / 100.0,
        )
        assertCameraParameters(
            requireNotNull(presets["kodak-power-flash-800"]),
            iso = 800,
            aperture = 10.0,
            focalLengthMm = 30.0,
            shutterSeconds = 1.0 / 125.0,
        )
        assertCameraParameters(
            requireNotNull(presets["fujifilm-quicksnap-flash-400"]),
            iso = 400,
            aperture = 10.0,
            focalLengthMm = 32.0,
            shutterSeconds = 1.0 / 140.0,
        )
        assertCameraParameters(
            requireNotNull(presets["fujifilm-c400-jelly"]),
            iso = 400,
            aperture = 11.0,
            focalLengthMm = 32.0,
            shutterSeconds = 1.0 / 125.0,
        )
    }

    @Test
    fun viewModelStartsWithDefaultPresetAndEvaluatesLiveMetering() {
        val viewModel = FilmPreviewViewModel()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = FilmPreviewEngine.presetEv100(funSaver),
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )

        val state = viewModel.state.value
        assertEquals(funSaver.id, state.selectedPreset?.id)
        assertEquals(0.0, state.evaluation?.sceneDeltaEv ?: Double.NaN, 0.0001)
        assertEquals(PreviewSceneRating.GOOD, state.evaluation?.rating)
    }

    @Test
    fun manualPresetIsFirstButBuiltInPresetRemainsDefault() {
        val state = FilmPreviewViewModel().state.value

        assertEquals(ManualCameraConfig.MANUAL_PRESET_ID, state.presets.first().id)
        assertEquals(funSaver.id, state.selectedPreset?.id)
    }

    @Test
    fun savedManualConfigIsSelectedAndUsedForPreviewEvaluation() {
        val store = InMemoryFilmPreviewSettingsStore()
        val viewModel = FilmPreviewViewModel(settingsStore = store)
        val config = ManualCameraConfig(
            iso = 200,
            shutterDenominator = 60,
            aperture = 8.0,
            focalLengthMm = 40.0,
        )

        assertTrue(
            viewModel.savePresetSettings(
                ManualCameraConfig.MANUAL_PRESET_ID,
                config,
            ),
        )
        val preset = requireNotNull(viewModel.state.value.selectedPreset)
        val referenceEv = FilmPreviewEngine.presetEv100(preset)
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = referenceEv,
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )

        assertEquals(ManualCameraConfig.MANUAL_PRESET_ID, preset.id)
        assertEquals(200, preset.film.iso)
        assertEquals(1.0 / 60.0, preset.shutterSeconds, 1e-9)
        assertEquals(8.0, preset.optics.aperture, 0.0)
        assertEquals(40.0, preset.optics.focalLengthMm, 0.0)
        assertEquals(0.0, viewModel.state.value.evaluation?.sceneDeltaEv ?: 1.0, 1e-9)
    }

    @Test
    fun manualConfigAndSelectionAreRestored() {
        val config = ManualCameraConfig(
            iso = 1600,
            shutterDenominator = 250,
            aperture = 16.0,
            focalLengthMm = 28.0,
        )
        val store = InMemoryFilmPreviewSettingsStore(
            FilmPreviewSettings(
                selectedPresetId = ManualCameraConfig.MANUAL_PRESET_ID,
                manualConfig = config,
            ),
        )

        val restored = FilmPreviewViewModel(settingsStore = store).state.value

        assertEquals(ManualCameraConfig.MANUAL_PRESET_ID, restored.selectedPreset?.id)
        assertEquals(config, restored.manualConfig)
        assertEquals(28.0, restored.selectedPreset?.optics?.focalLengthMm ?: 0.0, 0.0)
    }

    @Test
    fun selectingPresetClearsMeteringFromPreviousFieldOfView() {
        val viewModel = FilmPreviewViewModel()
        val meteredEv = FilmPreviewEngine.presetEv100(quickSnap)
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = meteredEv,
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )

        viewModel.selectPreset("fujifilm-c400-jelly")

        val state = viewModel.state.value
        assertNull(state.meteredEv100)
        assertNull(state.evaluation?.sceneDeltaEv)
        assertEquals(PreviewSceneRating.UNAVAILABLE, state.evaluation?.rating)
    }

    @Test
    fun freezeUsesCapturedSnapshotAndIgnoresLiveResults() {
        val viewModel = FilmPreviewViewModel()
        val initialEv = FilmPreviewEngine.presetEv100(funSaver)
        viewModel.onCameraReady()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = initialEv,
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )

        viewModel.freezePreview()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = initialEv + 2.0,
                measuredLuminance = 0.72,
                timestampNs = 2L,
            ),
        )

        val frozenState = viewModel.state.value
        assertTrue(frozenState.isFrozen)
        assertEquals(1, frozenState.freezeRequestId)
        assertEquals(initialEv, frozenState.meteredEv100 ?: Double.NaN, 0.0001)

        val capturedEv = initialEv + 1.0
        val capturedSnapshot = ExposureSnapshot(
            exposureMap = ExposureMap(
                width = 1,
                height = 1,
                pixelEv100 = floatArrayOf(capturedEv.toFloat()),
                timestampNs = 3L,
            ),
            meteredEv100 = capturedEv,
            timestampNs = 3L,
            revision = 0L,
        )
        viewModel.onFrozenSnapshot(requestId = 2, snapshot = capturedSnapshot)
        assertEquals(initialEv, viewModel.state.value.meteredEv100 ?: Double.NaN, 0.0001)

        viewModel.onFrozenSnapshot(requestId = 1, snapshot = capturedSnapshot)
        assertEquals(capturedEv, viewModel.state.value.meteredEv100 ?: Double.NaN, 0.0001)
        assertEquals(
            1.0,
            viewModel.state.value.evaluation?.sceneDeltaEv ?: Double.NaN,
            0.0001,
        )

        viewModel.resumeLivePreview()
        assertTrue(!viewModel.state.value.isFrozen)
    }

    private fun assertCameraParameters(
        preset: DisposableCameraPreset,
        iso: Int,
        aperture: Double,
        focalLengthMm: Double,
        shutterSeconds: Double,
    ) {
        assertEquals(iso, preset.film.iso)
        assertEquals(aperture, preset.optics.aperture, 0.0)
        assertEquals(focalLengthMm, preset.optics.focalLengthMm, 0.0)
        assertEquals(shutterSeconds, preset.shutterSeconds, 1e-12)
    }
}

private class InMemoryFilmPreviewSettingsStore(
    private var settings: FilmPreviewSettings = FilmPreviewSettings(),
) : FilmPreviewSettingsStore {
    override fun load() = settings

    override fun save(settings: FilmPreviewSettings): Boolean {
        this.settings = settings
        return true
    }
}
