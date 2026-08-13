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

        assertEquals(11.2877, reference, 0.0001)
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

        assertEquals(6, presets.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(presets.all { it.presetVersion > 0 })
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

        viewModel.selectPreset("fujifilm-quicksnap-waterproof-800")

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
}
