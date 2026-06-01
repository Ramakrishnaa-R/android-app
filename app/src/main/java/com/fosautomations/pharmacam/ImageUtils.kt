package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

object ImageUtils {

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
}