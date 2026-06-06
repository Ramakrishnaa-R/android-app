package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min
import kotlin.math.pow

object ImageUtils {

    // -----------------------------------------------------------------------
    // Existing filters
    // -----------------------------------------------------------------------

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p    = pixels[i]
            val gray = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun removeSpecularHighlights(bitmap: Bitmap): Bitmap {
        val width  = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8  and 0xFF
            val b = p         and 0xFF
            if (r > 240 && g > 240 && b > 240) {
                pixels[i] = (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    fun adaptiveThreshold(bitmap: Bitmap): Bitmap {
        val width  = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(width * height)

        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                rowSum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
            val rowAvg = (rowSum / width).toInt()

            for (x in 0 until width) {
                val p      = pixels[y * width + x]
                val gray   = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                val binary = if (gray >= rowAvg - 10) 255 else 0
                outPixels[y * width + x] = Color.rgb(binary, binary, binary)
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }

    fun preprocessForOCR(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p    = pixels[i]
            val gray = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8
            val e    = when {
                gray > 220 -> 255
                gray > 160 -> 220
                gray > 100 -> 150
                else       -> 50
            }
            pixels[i] = (0xFF shl 24) or (e shl 16) or (e shl 8) or e
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // -----------------------------------------------------------------------
    // NEW — Gamma correction
    //
    // gamma < 1.0  → brightens dark/underexposed labels (try 0.6)
    // gamma > 1.0  → darkens washed-out/overexposed labels (try 1.6)
    //
    // Uses a precomputed 256-entry LUT so per-pixel cost is just one array
    // lookup — safe to run on large bitmaps without ANR risk.
    // -----------------------------------------------------------------------
    fun applyGamma(source: Bitmap, gamma: Float): Bitmap {
        // Build LUT once
        val lut = IntArray(256) { i ->
            (255.0 * (i / 255.0).pow(gamma.toDouble())).toInt().coerceIn(0, 255)
        }
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = (p shr 16) and 0xFF   // already grayscale — R=G=B
            val mapped = lut[gray]
            pixels[i] = (0xFF shl 24) or (mapped shl 16) or (mapped shl 8) or mapped
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // -----------------------------------------------------------------------
    // NEW — CLAHE-lite (Contrast Limited Adaptive Histogram Equalization)
    //
    // Divides the image into [tileSize]×[tileSize] px tiles, equalises each
    // tile's histogram with a clip limit, then bilinearly blends neighbours.
    // Much better than global equalization for labels with uneven lighting
    // (e.g. one side shadowed, the other overexposed).
    //
    // [tileSize]  — tile side in pixels; 64 is a good default for ~800px images
    // [clipLimit] — fraction of histogram peak above which bins are clipped
    //               and redistributed; 2.0–3.0 is typical
    // -----------------------------------------------------------------------
    fun applyClahe(source: Bitmap, tileSize: Int = 64, clipLimit: Float = 2.5f): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Extract luma plane
        val luma = IntArray(w * h) { i -> (pixels[i] shr 16) and 0xFF }

        val cols = (w + tileSize - 1) / tileSize
        val rows = (h + tileSize - 1) / tileSize

        // Build CDF for each tile
        val cdfs = Array(rows) { row ->
            Array(cols) { col ->
                val x0 = col * tileSize
                val y0 = row * tileSize
                val x1 = min(x0 + tileSize, w)
                val y1 = min(y0 + tileSize, h)

                val hist = IntArray(256)
                for (y in y0 until y1) for (x in x0 until x1) hist[luma[y * w + x]]++

                val count = (x1 - x0) * (y1 - y0)
                val clipThresh = (clipLimit * count / 256).toInt().coerceAtLeast(1)

                // Clip and redistribute
                var excess = 0
                for (v in 0..255) {
                    if (hist[v] > clipThresh) { excess += hist[v] - clipThresh; hist[v] = clipThresh }
                }
                val perBin = excess / 256
                for (v in 0..255) hist[v] += perBin

                // Build normalised CDF
                val cdf = IntArray(256)
                var cum = 0
                for (v in 0..255) { cum += hist[v]; cdf[v] = cum }
                val cdfMin = cdf.first { it > 0 }
                val scale = 255.0 / (count - cdfMin).coerceAtLeast(1)
                IntArray(256) { v -> ((cdf[v] - cdfMin) * scale).toInt().coerceIn(0, 255) }
            }
        }

        // Bilinear interpolation between tile CDFs
        val output = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val tileX = x.toFloat() / tileSize - 0.5f
                val tileY = y.toFloat() / tileSize - 0.5f
                val tx = tileX.coerceIn(0f, (cols - 1).toFloat())
                val ty = tileY.coerceIn(0f, (rows - 1).toFloat())
                val col0 = tx.toInt().coerceIn(0, cols - 1)
                val row0 = ty.toInt().coerceIn(0, rows - 1)
                val col1 = (col0 + 1).coerceIn(0, cols - 1)
                val row1 = (row0 + 1).coerceIn(0, rows - 1)
                val fx = tx - col0
                val fy = ty - row0

                val v = luma[y * w + x]
                val v00 = cdfs[row0][col0][v].toFloat()
                val v10 = cdfs[row0][col1][v].toFloat()
                val v01 = cdfs[row1][col0][v].toFloat()
                val v11 = cdfs[row1][col1][v].toFloat()

                val mapped = (v00 * (1 - fx) * (1 - fy) +
                              v10 * fx       * (1 - fy) +
                              v01 * (1 - fx) * fy       +
                              v11 * fx       * fy).toInt().coerceIn(0, 255)
                output[y * w + x] = (0xFF shl 24) or (mapped shl 16) or (mapped shl 8) or mapped
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(output, 0, w, 0, 0, w, h)
        return out
    }

    // -----------------------------------------------------------------------
    // NEW — Strong unsharp mask (more aggressive than LabelOcrHelper.sharpenLight)
    //
    // Good for slightly blurry captures where the light sharpen isn't enough.
    // Uses a 5×5 blur kernel for the reference then subtracts twice (amount=2).
    // -----------------------------------------------------------------------
    fun applyStrongSharpen(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 5 || h < 5) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        fun luma(p: Int) = (p shr 16) and 0xFF   // R=G=B for grayscale

        // Box blur 5×5
        val blur = IntArray(w * h)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                var sum = 0; var count = 0
                for (dy in -2..2) for (dx in -2..2) { sum += luma(pixels[(y+dy)*w+(x+dx)]); count++ }
                val b = sum / count
                blur[y * w + x] = b
            }
        }

        val out = pixels.copyOf()
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val c = luma(pixels[y * w + x])
                val b = blur[y * w + x]
                // unsharp: original + amount*(original - blur)
                val sharp = (c + 2 * (c - b)).coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (sharp shl 16) or (sharp shl 8) or sharp
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}