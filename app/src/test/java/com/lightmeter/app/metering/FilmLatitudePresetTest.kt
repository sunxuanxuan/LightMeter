package com.lightmeter.app.metering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmLatitudePresetTest {
    @Test
    fun vision3PresetsUsePracticalThresholdsFromReference() {
        val film5207 = FilmLatitudePreset.KODAK_VISION3_250D_5207
        val film5203 = FilmLatitudePreset.KODAK_VISION3_50D_5203

        assertEquals(14.0 / 3.0, film5207.highlightStops, 0.0001)
        assertEquals(3.0, film5207.shadowStops, 0.0)
        assertEquals(5.0, film5203.highlightStops, 0.0)
        assertEquals(3.0, film5203.shadowStops, 0.0)
        assertEquals(FilmLatitudeEvidence.OFFICIAL_CURVE, film5203.evidence)
    }

    @Test
    fun allPresetThresholdsFitExposureRiskControls() {
        FilmLatitudePreset.entries.forEach { preset ->
            assertTrue(preset.highlightStops in (1.0 / 3.0)..8.0)
            assertTrue(preset.shadowStops in (1.0 / 3.0)..8.0)
            assertEquals(
                (preset.highlightStops * 3.0).toInt().toDouble(),
                preset.highlightStops * 3.0,
                0.0001,
            )
            assertEquals(
                (preset.shadowStops * 3.0).toInt().toDouble(),
                preset.shadowStops * 3.0,
                0.0001,
            )
        }
    }

    @Test
    fun reversalPresetsUseReferenceFilmLatitude() {
        val presets = listOf(
            FilmLatitudePreset.KODAK_E100,
            FilmLatitudePreset.KODAK_EKTACHROME_100D_5294,
        )

        presets.forEach { preset ->
            assertEquals(2.0 / 3.0, preset.highlightStops, 0.0001)
            assertEquals(5.0 / 3.0, preset.shadowStops, 0.0001)
        }
    }

    @Test
    fun includesEveryFilmGroupFromReferenceTable() {
        assertEquals(11, FilmLatitudePreset.entries.size)
    }
}
