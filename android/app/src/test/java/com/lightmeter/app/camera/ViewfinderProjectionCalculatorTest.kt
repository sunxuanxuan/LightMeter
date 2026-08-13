package com.lightmeter.app.camera

import com.lightmeter.app.exposure.FrameFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ViewfinderProjectionCalculatorTest {
    private val cameraOptics = CameraOptics(
        sensorWidthMm = 7.2,
        sensorHeightMm = 5.4,
        focalLengthMm = 5.0,
    )

    @Test
    fun fitZoomMakesLongEdgeFillPreview() {
        val projection = ViewfinderProjectionCalculator.calculate(
            previewAspectRatio = 0.5,
            frameFormat = FrameFormat.FILM_645,
            targetFocalLengthMm = 75.0,
            cameraOptics = cameraOptics,
        )

        val fittedLongEdge = max(
            projection.widthFractionAt(projection.fitZoomRatio),
            projection.heightFractionAt(projection.fitZoomRatio),
        )

        assertEquals(1.0, fittedLongEdge, 0.0001)
    }

    @Test
    fun limitedZoomKeepsRemainingViewfinderBorder() {
        val projection = ViewfinderProjection(
            baseWidthFraction = 0.25,
            baseHeightFraction = 0.4,
        )

        assertEquals(2.5, projection.fitZoomRatio, 0.0001)
        assertEquals(0.5, projection.widthFractionAt(2.0), 0.0001)
        assertEquals(0.8, projection.heightFractionAt(2.0), 0.0001)
    }

    @Test
    fun longerFocalLengthRequiresMoreZoom() {
        val wide = ViewfinderProjectionCalculator.calculate(
            previewAspectRatio = 0.5,
            frameFormat = FrameFormat.FILM_135,
            targetFocalLengthMm = 35.0,
            cameraOptics = cameraOptics,
        )
        val tele = ViewfinderProjectionCalculator.calculate(
            previewAspectRatio = 0.5,
            frameFormat = FrameFormat.FILM_135,
            targetFocalLengthMm = 100.0,
            cameraOptics = cameraOptics,
        )

        assertTrue(tele.fitZoomRatio > wide.fitZoomRatio)
    }

    @Test
    fun fittedViewfinderUsesSelectedFrameFormatAspectRatio() {
        val previewWidth = 1080.0
        val previewHeight = 2160.0
        val previewAspectRatio = previewWidth / previewHeight

        FrameFormat.entries.forEach { format ->
            val expectedPortraitAspectRatio = min(
                format.frameWidthMm,
                format.frameHeightMm,
            ) / max(
                format.frameWidthMm,
                format.frameHeightMm,
            )

            listOf(20.0, 50.0, 120.0).forEach { focalLength ->
                val projection = ViewfinderProjectionCalculator.calculate(
                    previewAspectRatio = previewAspectRatio,
                    frameFormat = format,
                    targetFocalLengthMm = focalLength,
                    cameraOptics = cameraOptics,
                )
                val renderedAspectRatio =
                    projection.widthFractionAt(projection.fitZoomRatio) * previewWidth /
                        (
                            projection.heightFractionAt(projection.fitZoomRatio) *
                                previewHeight
                            )

                assertEquals(
                    "${format.displayName} ${focalLength}mm",
                    expectedPortraitAspectRatio,
                    renderedAspectRatio,
                    0.0001,
                )
            }
        }
    }

    @Test
    fun sameHorizontalFieldOfViewAcrossFormatsRequiresSameZoomRatio() {
        val film135FocalLength = 50.0
        val film66HorizontalEquivalentFocalLength =
            film135FocalLength *
                min(
                    FrameFormat.FILM_66.frameWidthMm,
                    FrameFormat.FILM_66.frameHeightMm,
                ) /
                min(
                    FrameFormat.FILM_135.frameWidthMm,
                    FrameFormat.FILM_135.frameHeightMm,
                )
        val film135 = ViewfinderProjectionCalculator.calculate(
            previewAspectRatio = 0.5,
            frameFormat = FrameFormat.FILM_135,
            targetFocalLengthMm = film135FocalLength,
            cameraOptics = cameraOptics,
        )
        val film66 = ViewfinderProjectionCalculator.calculate(
            previewAspectRatio = 0.5,
            frameFormat = FrameFormat.FILM_66,
            targetFocalLengthMm = film66HorizontalEquivalentFocalLength,
            cameraOptics = cameraOptics,
        )

        assertEquals(film135.fitZoomRatio, film66.fitZoomRatio, 0.0001)
    }
}
