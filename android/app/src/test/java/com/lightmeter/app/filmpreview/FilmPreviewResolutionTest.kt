package com.lightmeter.app.filmpreview

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmPreviewResolutionTest {
    @Test
    fun highResolutionPortraitIsReducedTo1920PixelLongEdge() {
        assertEquals(Pair(1280, 1920), previewBitmapDimensions(3000, 4500))
    }

    @Test
    fun existing1080pOrSmallerImageIsNotUpscaled() {
        assertEquals(Pair(1080, 1620), previewBitmapDimensions(1080, 1620))
    }
}
