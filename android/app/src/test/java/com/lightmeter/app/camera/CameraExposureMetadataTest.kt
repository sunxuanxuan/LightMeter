package com.lightmeter.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraExposureMetadataTest {
    @Test
    fun effectiveSensitivityIncludesPostRawBoost() {
        val metadata = CameraExposureMetadata(
            exposureTimeNs = 1_000_000L,
            sensorSensitivity = 800,
            postRawSensitivityBoost = 200,
            aperture = 1.8,
            timestampNs = 1L,
        )

        assertEquals(1600.0, metadata.effectiveSensitivity, 0.0)
    }
}
