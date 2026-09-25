package com.kenny.tellofollow.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Saves the latest decoded frame as JPEG under Pictures/TelloFollow via MediaStore.
 */
class PhotoCapture(private val context: Context) {

    companion object {
        private const val TAG = "PhotoCapture"
        private const val RELATIVE_PATH = "Pictures/TelloFollow"
        private const val QUALITY = 92
    }

    /**
     * @return content Uri on success, null on failure
     */
    suspend fun saveJpeg(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val name = "Tello_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Images.Media.DATA,
                    "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)}/TelloFollow/$name"
                )
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                    throw IOException("JPEG compress failed")
                }
            } ?: throw IOException("null output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "saved $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
