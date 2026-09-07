package com.lightmeter.app.filmpreview

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object FilmPhotoSaver {
    fun save(
        context: Context,
        bitmap: Bitmap,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): Result<Uri> = runCatching {
        val displayName = "${FILE_PREFIX}${
            SimpleDateFormat(FILE_DATE_PATTERN, Locale.US).format(Date(capturedAtMillis))
        }.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, bitmap, displayName)
        } else {
            saveToLegacyPictures(context, bitmap, displayName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore image")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Unable to encode simulated image"
                }
            } ?: error("Unable to open MediaStore output")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyPictures(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): Uri {
        val albumDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_NAME,
        )
        check(albumDirectory.exists() || albumDirectory.mkdirs()) {
            "Unable to create picture directory"
        }
        val outputFile = File(albumDirectory, displayName)
        try {
            FileOutputStream(outputFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Unable to encode simulated image"
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outputFile.absolutePath),
                arrayOf(MIME_TYPE),
                null,
            )
            return Uri.fromFile(outputFile)
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private const val ALBUM_NAME = "FilmLightMeter"
    private const val FILE_PREFIX = "FilmLightMeter_"
    private const val FILE_DATE_PATTERN = "yyyyMMdd_HHmmss_SSS"
    private const val MIME_TYPE = "image/jpeg"
    private const val JPEG_QUALITY = 95
}
