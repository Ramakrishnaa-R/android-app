package com.fosautomations.pharmacam

object BitmapHolder {
    var bitmap: android.graphics.Bitmap? = null
    var wideBitmap: android.graphics.Bitmap? = null

    // ── Real pipeline snapshots saved during actual OCR ──
    var filteredBitmap: android.graphics.Bitmap? = null    // after applyBlackPointSaturation()

    /** The name of the filter that won the OCR matching (always "bp-sat" now) */
    var winningFilter: String? = null

    // Filter OCR text maps to show in debug page
    var filterOcrTexts: Map<String, String>? = null
    var wideFilterOcrTexts: Map<String, String>? = null

    /** Recycle and clear all pipeline snapshots (call from ImageProcessingActivity.onDestroy). */
    fun clearPipelineSnapshots() {
        filteredBitmap?.recycle();  filteredBitmap  = null
        winningFilter = null
        filterOcrTexts = null
        wideFilterOcrTexts = null
    }
}
