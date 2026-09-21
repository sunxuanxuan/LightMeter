package com.lightmeter.app.metering

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

internal object ImageProxyBitmapConverter {
    fun convert(image: ImageProxy): Bitmap {
        val source = image.toBitmap()
        val cropRect = image.cropRect
        val safeLeft = cropRect.left.coerceIn(0, source.width - 1)
        val safeTop = cropRect.top.coerceIn(0, source.height - 1)
        val safeRight = cropRect.right.coerceIn(safeLeft + 1, source.width)
        val safeBottom = cropRect.bottom.coerceIn(safeTop + 1, source.height)
        val cropped = if (
            safeLeft == 0 &&
            safeTop == 0 &&
            safeRight == source.width &&
            safeBottom == source.height
        ) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                safeLeft,
                safeTop,
                safeRight - safeLeft,
                safeBottom - safeTop,
            ).also { source.recycle() }
        }
        val rotationDegrees = image.imageInfo.rotationDegrees
        return if (rotationDegrees == 0) {
            cropped
        } else {
            Bitmap.createBitmap(
                cropped,
                0,
                0,
                cropped.width,
                cropped.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                true,
            ).also { rotated ->
                if (rotated !== cropped) cropped.recycle()
            }
        }
    }
}
