package com.fosautomations.pharmacam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves shutter / OCR crops for manual inspection.
 * Writes crop files to the app-private scan_debug folder.
 */
object ScanDebugImageSaver {

    private const val TAG = "PharmaCam_DebugSave"

    /** Set to false when you no longer need saved captures. */
    const val ENABLED = true

    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    data class SavedCapture(val appFile: File)

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

            Log.i(
                TAG,
                "Saved $label (${appFile.length()} bytes)\n" +
                    "  file: ${appFile.absolutePath}"
            )
            SavedCapture(appFile = appFile)
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

    fun showSavedToast(context: Context, saves: List<SavedCapture>) {
        if (!ENABLED || saves.isEmpty()) return
        val appPath = saves.first().appFile.parentFile?.absolutePath.orEmpty()
        Toast.makeText(
            context,
            "Saved ${saves.size} crop file(s)\n$appPath",
            Toast.LENGTH_LONG
        ).show()
    }

    fun logWhereToFind(context: Context) {
        Log.i(
            TAG,
            "Debug save ON. After scan, look at:\n" +
                "  ${appDebugDir(context).absolutePath}\n" +
                "  adb: adb shell run-as com.fosautomations.pharmacam ls -la files/scan_debug"
        )
    }
}
