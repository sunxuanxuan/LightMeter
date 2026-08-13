package com.lightmeter.app.ui

import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.FilmLatitudePreset
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.MeteringResult
import com.lightmeter.app.settings.AppSettings
import com.lightmeter.app.settings.AppSettingsStore
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
        val store = InMemoryAppSettingsStore()
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
    }
}

private class InMemoryAppSettingsStore : AppSettingsStore {
    private var settings = AppSettings()

    override fun load() = settings

    override fun save(settings: AppSettings): Boolean {
        this.settings = settings
        return true
    }
}
