package com.lightmeter.app.metering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvLuminanceTest {
    @Test
    fun videoRangeBlackAndWhiteMapToNormalizedEndpoints() {
        assertEquals(
            0.0,
            YuvLuminance.normalized(16, YuvLuminanceRange.LIMITED),
            0.0,
        )
        assertEquals(
            1.0,
            YuvLuminance.normalized(235, YuvLuminanceRange.LIMITED),
            0.0,
        )
    }

    @Test
    fun valuesOutsideVideoRangeAreClamped() {
        assertEquals(
            0.0,
            YuvLuminance.normalized(0, YuvLuminanceRange.LIMITED),
            0.0,
        )
        assertEquals(
            1.0,
            YuvLuminance.normalized(255, YuvLuminanceRange.LIMITED),
            0.0,
        )
    }

    @Test
    fun highlightClippingStartsAtVideoRangeWhiteLevel() {
        assertFalse(YuvLuminance.isHighlightClipped(234, YuvLuminanceRange.LIMITED))
        assertTrue(YuvLuminance.isHighlightClipped(235, YuvLuminanceRange.LIMITED))
        assertTrue(YuvLuminance.isHighlightClipped(255, YuvLuminanceRange.LIMITED))
    }

    @Test
    fun fullRangeBlackAndWhiteMapToNormalizedEndpoints() {
        assertEquals(
            0.0,
            YuvLuminance.normalized(0, YuvLuminanceRange.FULL),
            0.0,
        )
        assertEquals(
            1.0,
            YuvLuminance.normalized(255, YuvLuminanceRange.FULL),
            0.0,
        )
    }

    @Test
    fun fullRangeHighlightClippingStartsAtFullRangeWhiteLevel() {
        assertFalse(YuvLuminance.isHighlightClipped(235, YuvLuminanceRange.FULL))
        assertFalse(YuvLuminance.isHighlightClipped(254, YuvLuminanceRange.FULL))
        assertTrue(YuvLuminance.isHighlightClipped(255, YuvLuminanceRange.FULL))
    }
}
