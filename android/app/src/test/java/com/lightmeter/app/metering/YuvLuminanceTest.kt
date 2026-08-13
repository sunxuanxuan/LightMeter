package com.lightmeter.app.metering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvLuminanceTest {
    @Test
    fun videoRangeBlackAndWhiteMapToNormalizedEndpoints() {
        assertEquals(0.0, YuvLuminance.normalized(16), 0.0)
        assertEquals(1.0, YuvLuminance.normalized(235), 0.0)
    }

    @Test
    fun valuesOutsideVideoRangeAreClamped() {
        assertEquals(0.0, YuvLuminance.normalized(0), 0.0)
        assertEquals(1.0, YuvLuminance.normalized(255), 0.0)
    }

    @Test
    fun highlightClippingStartsAtVideoRangeWhiteLevel() {
        assertFalse(YuvLuminance.isHighlightClipped(234))
        assertTrue(YuvLuminance.isHighlightClipped(235))
        assertTrue(YuvLuminance.isHighlightClipped(255))
    }
}
