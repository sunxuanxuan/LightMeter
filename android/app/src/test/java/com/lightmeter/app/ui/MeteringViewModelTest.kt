package com.lightmeter.app.ui

import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.ExposureMap
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.FilmLatitudePreset
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.MeteringResult
import com.lightmeter.app.settings.AppSettings
import com.lightmeter.app.settings.AppSettingsStore
import com.lightmeter.app.ui.theme.AppThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringViewModelTest {
    @Test
    fun brightSceneRecommendationStaysWithinExposureTolerance() {
        val viewModel = MeteringViewModel()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 17.0,
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )

        val recommendation = viewModel.state.value.primaryExposure
        assertNotNull(recommendation)
        assertTrue(recommendation!!.error <= 1.0 / 6.0)
    }

    @Test
    fun lowLightRecommendationPrioritizesCorrectExposureWhenNoSafeShutterExists() {
        val viewModel = MeteringViewModel()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = -3.0,
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )

        val recommendation = viewModel.state.value.primaryExposure
        assertNotNull(recommendation)
        assertTrue(recommendation!!.error <= 1.0 / 6.0)
        assertTrue(recommendation.shutterSeconds > 1.0 / 60.0)
    }

    @Test
    fun exposureCompensationUsesThirdStopSequenceWithinThreeStops() {
        assertEquals(19, MeteringViewModel.exposureCompensationOptions.size)
        assertEquals(-3.0, MeteringViewModel.exposureCompensationOptions.first(), 0.0)
        assertEquals(3.0, MeteringViewModel.exposureCompensationOptions.last(), 0.0)
        assertEquals(
            listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0),
            MeteringViewModel.exposureCompensationOptions.drop(9).take(4),
        )

        val viewModel = MeteringViewModel()
        viewModel.selectExposureCompensation(6.0)
        assertEquals(3.0, viewModel.state.value.exposureCompensation, 0.0)
        viewModel.selectExposureCompensation(-6.0)
        assertEquals(-3.0, viewModel.state.value.exposureCompensation, 0.0)
    }

    @Test
    fun latitudeAdjustmentUsesExactThirdStop() {
        val viewModel = MeteringViewModel()

        viewModel.adjustHighlightLatitude(MeteringViewModel.EV_THIRD_STEP)
        viewModel.adjustShadowLatitude(-MeteringViewModel.EV_THIRD_STEP)

        assertEquals(13.0 / 3.0, viewModel.state.value.highlightLatitudeStops, 0.0001)
        assertEquals(8.0 / 3.0, viewModel.state.value.shadowLatitudeStops, 0.0001)
    }

    @Test
    fun savedSettingsAreRestoredByANewViewModel() {
        val store = InMemoryAppSettingsStore(
            AppSettings(themeStyle = AppThemeStyle.LIGHT),
        )
        val firstViewModel = MeteringViewModel(store)
        firstViewModel.selectIso(400)
        firstViewModel.selectExposureCompensation(2.0 / 3.0)
        firstViewModel.selectFrameFormat(FrameFormat.FILM_66)
        firstViewModel.selectFocalLength(80.0)
        firstViewModel.selectMeteringPreset(MeteringMode.SPOT)
        firstViewModel.selectFilmLatitudePreset(FilmLatitudePreset.KODAK_E100)

        assertTrue(firstViewModel.saveSettings())

        val restoredState = MeteringViewModel(store).state.value
        assertEquals(400, restoredState.selectedIso)
        assertEquals(2.0 / 3.0, restoredState.exposureCompensation, 0.0001)
        assertEquals(FrameFormat.FILM_66, restoredState.frameFormat)
        assertEquals(80.0, restoredState.focalLengthMm, 0.0)
        assertEquals(MeteringMode.SPOT, restoredState.meteringPreset)
        assertEquals(MeteringMode.SPOT, restoredState.meteringMode)
        assertEquals(FilmLatitudePreset.KODAK_E100, restoredState.filmLatitudePreset)
        assertEquals(2.0 / 3.0, restoredState.highlightLatitudeStops, 0.0001)
        assertEquals(5.0 / 3.0, restoredState.shadowLatitudeStops, 0.0001)
        assertEquals(AppThemeStyle.LIGHT, store.load().themeStyle)
    }

    @Test
    fun appThemeDefaultsToDark() {
        assertEquals(AppThemeStyle.DARK, AppSettings().themeStyle)
    }

    @Test
    fun calibrationOffsetIsRestoredAndApplied() {
        val store = InMemoryAppSettingsStore(
            AppSettings(
                calibrationOffset = 0.4,
                grayCardCalibrationCompleted = true,
                grayCardCalibrationPromptSeen = true,
            ),
        )
        val viewModel = MeteringViewModel(store)

        assertEquals(0.4, viewModel.state.value.calibrationOffset, 1e-9)

        viewModel.applyCalibrationOffset(-0.2)

        assertEquals(-0.2, viewModel.state.value.calibrationOffset, 1e-9)
    }

    @Test
    fun focalLengthChangeKeepsLastResultUntilCurrentRevisionArrives() {
        val viewModel = MeteringViewModel()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 10.0,
                measuredLuminance = 0.18,
                timestampNs = 1L,
                revision = 0L,
            ),
        )
        val previousState = viewModel.state.value

        viewModel.selectFocalLength(80.0)

        val zoomingState = viewModel.state.value
        assertEquals(1L, zoomingState.meteringRevision)
        assertEquals(previousState.ev100Metered, zoomingState.ev100Metered)
        assertEquals(previousState.evTarget, zoomingState.evTarget)
        assertEquals(previousState.primaryExposure, zoomingState.primaryExposure)
        assertEquals(previousState.equivalentExposures, zoomingState.equivalentExposures)

        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 20.0,
                measuredLuminance = 0.5,
                timestampNs = 2L,
                revision = 0L,
            ),
        )
        assertEquals(10.0, viewModel.state.value.ev100Metered!!, 0.0)

        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 11.0,
                measuredLuminance = 0.2,
                timestampNs = 3L,
                revision = 1L,
            ),
        )
        assertEquals(11.0, viewModel.state.value.ev100Metered!!, 0.0)
        assertNotNull(viewModel.state.value.primaryExposure)
    }

    @Test
    fun unchangedFocalLengthDoesNotInvalidateMetering() {
        val viewModel = MeteringViewModel()

        viewModel.selectFocalLength(viewModel.state.value.focalLengthMm)

        assertEquals(0L, viewModel.state.value.meteringRevision)
    }

    @Test
    fun frameFormatChangeKeepsResultUntilReboundCameraFrameArrives() {
        val viewModel = MeteringViewModel()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 10.0,
                measuredLuminance = 0.18,
                timestampNs = 1L,
                revision = 0L,
            ),
        )
        val previousExposure = viewModel.state.value.primaryExposure

        viewModel.selectFrameFormat(FrameFormat.FILM_66)

        val rebindingState = viewModel.state.value
        assertEquals(1L, rebindingState.meteringRevision)
        assertEquals(10.0, rebindingState.ev100Metered!!, 0.0)
        assertEquals(previousExposure, rebindingState.primaryExposure)

        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 11.0,
                measuredLuminance = 0.2,
                timestampNs = 2L,
                revision = 1L,
            ),
        )
        assertEquals(11.0, viewModel.state.value.ev100Metered!!, 0.0)
        assertNotNull(viewModel.state.value.primaryExposure)
    }

    @Test
    fun freezeUsesCapturedSnapshotForDisplayedEvAndRecommendation() {
        val viewModel = MeteringViewModel()
        viewModel.onCameraReady()
        viewModel.onMeteringResult(
            MeteringResult(
                ev100 = 10.0,
                measuredLuminance = 0.18,
                timestampNs = 1L,
            ),
        )
        viewModel.freezePreview()
        val frozenState = viewModel.state.value
        val snapshot = ExposureSnapshot(
            exposureMap = ExposureMap(
                width = 1,
                height = 1,
                pixelEv100 = floatArrayOf(12.0f),
                timestampNs = 2L,
                revision = frozenState.meteringRevision,
            ),
            meteredEv100 = 12.0,
            timestampNs = 2L,
            revision = frozenState.meteringRevision,
        )

        viewModel.onFrozenSnapshot(frozenState.freezeRequestId, snapshot)

        assertEquals(12.0, viewModel.state.value.ev100Metered!!, 0.0)
        assertEquals(12.0, viewModel.state.value.evTarget!!, 0.0)
        assertNotNull(viewModel.state.value.primaryExposure)
    }

    @Test
    fun repeatedCameraOpticsAfterFrameRebindDoesNotInvalidateAgain() {
        val viewModel = MeteringViewModel()
        val optics = CameraOptics(
            sensorWidthMm = 6.4,
            sensorHeightMm = 4.8,
            focalLengthMm = 4.2,
        )
        viewModel.onCameraOpticsAvailable(optics)
        val revisionAfterOptics = viewModel.state.value.meteringRevision

        viewModel.selectFrameFormat(FrameFormat.FILM_66)
        val revisionAfterFormat = viewModel.state.value.meteringRevision
        viewModel.onCameraOpticsAvailable(optics)

        assertEquals(revisionAfterOptics + 1L, revisionAfterFormat)
        assertEquals(revisionAfterFormat, viewModel.state.value.meteringRevision)
    }
}

private class InMemoryAppSettingsStore(
    private var settings: AppSettings = AppSettings(),
) : AppSettingsStore {

    override fun load() = settings

    override fun save(settings: AppSettings): Boolean {
        this.settings = settings
        return true
    }
}
