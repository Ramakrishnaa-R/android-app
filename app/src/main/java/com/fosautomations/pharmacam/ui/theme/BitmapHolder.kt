package com.fosautomations.pharmacam

object BitmapHolder {
    var bitmap: android.graphics.Bitmap? = null
    var wideBitmap: android.graphics.Bitmap? = null

    // ── Real pipeline snapshots saved during actual OCR (square crop only) ──
    var grayscaleBitmap: android.graphics.Bitmap? = null   // after toGrayscale()
    var noGlareBitmap: android.graphics.Bitmap? = null     // after removeSpecularHighlights()
    var enhancedBitmap: android.graphics.Bitmap? = null    // Standard
    var binarizedBitmap: android.graphics.Bitmap? = null   // Binarized
    var claheBitmap: android.graphics.Bitmap? = null       // CLAHE
    var gammaBrightBitmap: android.graphics.Bitmap? = null // Gamma Bright
    var gammaDarkBitmap: android.graphics.Bitmap? = null   // Gamma Dark
    var sharpenBitmap: android.graphics.Bitmap? = null     // Strong Sharpen

    /** The name of the filter that won the OCR matching (e.g., "standard", "clahe", "gamma-bright", etc.) */
    var winningFilter: String? = null

    // Filter OCR text maps to show in debug page
    var filterOcrTexts: Map<String, String>? = null
    var wideFilterOcrTexts: Map<String, String>? = null

    /** Recycle and clear all pipeline snapshots (call from ImageProcessingActivity.onDestroy). */
    fun clearPipelineSnapshots() {
        grayscaleBitmap?.recycle(); grayscaleBitmap = null
        noGlareBitmap?.recycle();   noGlareBitmap   = null
        enhancedBitmap?.recycle();  enhancedBitmap  = null
        binarizedBitmap?.recycle(); binarizedBitmap = null
        claheBitmap?.recycle();     claheBitmap     = null
        gammaBrightBitmap?.recycle(); gammaBrightBitmap = null
        gammaDarkBitmap?.recycle();   gammaDarkBitmap   = null
        sharpenBitmap?.recycle();     sharpenBitmap     = null
        winningFilter = null
        filterOcrTexts = null
        wideFilterOcrTexts = null
    }
}
