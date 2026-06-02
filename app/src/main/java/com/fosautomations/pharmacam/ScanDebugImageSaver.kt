package com.fosautomations.pharmacam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves shutter / OCR crops for manual inspection.
 * Writes to app folder + public Pictures/PharmaCam (visible in Gallery / file apps).
 */
object ScanDebugImageSaver {

    private const val TAG = "PharmaCam_DebugSave"

    /** Set to false when you no longer need saved captures. */
    const val ENABLED = true

    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    data class SavedCapture(
        val appFile: File,
        val publicUri: Uri? = null
    )

    /** App-private folder (adb / Device File Explorer). */
    fun appDebugDir(context: Context): File =
        File(context.filesDir, "scan_debug").also { it.mkdirs() }

    fun saveCapture(context: Context, bitmap: Bitmap, label: String): SavedCapture? {
        if (!ENABLED) return null
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap already recycled — cannot save $label")
            return null
        }
        return try {
            val appDir = appDebugDir(context)
            val appFile = File(appDir, "${timestampFormat.format(Date())}_$label.jpg")
            writeJpeg(appFile, bitmap)

            val publicUri = saveToPublicPictures(context, bitmap, "${label}_${appFile.name}")

            Log.i(
                TAG,
                "Saved $label (${appFile.length()} bytes)\n" +
                    "  app: ${appFile.absolutePath}\n" +
                    "  gallery: ${publicUri ?: "n/a"}"
            )
            SavedCapture(appFile = appFile, publicUri = publicUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $label capture", e)
            null
        }
    }

    private fun writeJpeg(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw IllegalStateException("Bitmap.compress returned false")
            }
        }
    }

    /** Pictures/PharmaCam on device — easy to find in Gallery and Downloads. */
    private fun saveToPublicPictures(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val name = if (displayName.endsWith(".jpg", ignoreCase = true)) displayName else "$displayName.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PharmaCam"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                resolver.delete(uri, null, null)
                return null
            }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    fun showSavedToast(context: Context, saves: List<SavedCapture>) {
        if (!ENABLED || saves.isEmpty()) return
        val appPath = saves.first().appFile.parentFile?.absolutePath.orEmpty()
        val galleryHint = "Also in Gallery → Pictures → PharmaCam"
        Toast.makeText(
            context,
            "Saved ${saves.size} photo(s)\n$appPath\n$galleryHint",
            Toast.LENGTH_LONG
        ).show()
    }

    fun logWhereToFind(context: Context) {
        Log.i(
            TAG,
            "Debug save ON. After scan, look at:\n" +
                "  1) Gallery app → Pictures → PharmaCam\n" +
                "  2) ${appDebugDir(context).absolutePath}\n" +
                "  3) adb: adb shell run-as com.fosautomations.pharmacam ls -la files/scan_debug"
        )
    }
}
