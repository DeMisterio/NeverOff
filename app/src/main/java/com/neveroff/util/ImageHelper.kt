package com.neveroff.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageHelper {
    /**
     * Copies an image from a content URI to the app's internal files dir
     * and returns the absolute file path (which can be used by AsyncImage).
     */
    fun copyImageToInternalStorage(context: Context, uri: Uri, folderName: String = "wallpapers"): String? {
        return try {
            val folder = File(context.filesDir, folderName)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            val file = File(folder, "${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    fun compressImage(context: Context, uri: Uri, maxWidth: Int = 800, maxHeight: Int = 800, quality: Int = 80): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = android.graphics.BitmapFactory.Options()
                options.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeStream(input, null, options)
                
                var inSampleSize = 1
                if (options.outHeight > maxHeight || options.outWidth > maxWidth) {
                    val halfHeight: Int = options.outHeight / 2
                    val halfWidth: Int = options.outWidth / 2
                    while (halfHeight / inSampleSize >= maxHeight && halfWidth / inSampleSize >= maxWidth) {
                        inSampleSize *= 2
                    }
                }
                
                context.contentResolver.openInputStream(uri)?.use { input2 ->
                    options.inJustDecodeBounds = false
                    options.inSampleSize = inSampleSize
                    val bitmap = android.graphics.BitmapFactory.decodeStream(input2, null, options)
                    val out = java.io.ByteArrayOutputStream()
                    bitmap?.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** JPEG-encodes an already-in-memory bitmap (e.g. the output of the image editor) for upload. */
    fun compressBitmap(bitmap: android.graphics.Bitmap, quality: Int = 85): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
