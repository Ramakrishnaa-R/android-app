package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.graphics.Color

object ImageUtils {

    // -----------------------------------------------------------------------
    // Base helpers (still used by the pipeline)
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

    // -----------------------------------------------------------------------
    // PRIMARY OCR FILTER — Black Point 82 + Full Saturation Boost (COLOR output)
    //
    // Works entirely in HSV colour space:
    //   1. S (Saturation) is pushed to the requested value (default 1.0 = 100%).
    //      This makes red, blue, cyan text pop vividly against metallic/foil
    //      backgrounds exactly like the reference photo supplied by the user.
    //   2. V (Value / brightness) is remapped with a black-point crush:
    //      any pixel whose V falls below blackPoint/255 is set to pure black;
    //      the remaining range [blackPoint/255 .. 1.0] is stretched back to
    //      [0 .. 1.0] so bright areas stay bright and mid-tones gain contrast.
    //   3. Output is a full-colour Bitmap — ML Kit handles colour just fine and
    //      coloured text (red on silver, white on blue) reads much better in
    //      colour than after an early grayscale collapse.
    //
    // Parameters:
    //   blackPoint  — luminance threshold [0–255].  Default 82 (≈32 % of max).
    //   saturation  — target HSV saturation [0.0–1.0].  Default 1.0 (= 100 %).
    // -----------------------------------------------------------------------
    fun applyBlackPointSaturation(
        source: Bitmap,
        blackPoint: Int   = 82,
        saturation: Float = 1.0f
    ): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val hsv   = FloatArray(3)
        val bpNorm = blackPoint / 255.0f   // normalised black-point threshold

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF

            // ── Step 1: HSV conversion ──────────────────────────────────────
            Color.RGBToHSV(r, g, b, hsv)

            // ── Step 2: boost saturation ────────────────────────────────────
            hsv[1] = saturation

            // ── Step 3: black-point crush on Value channel ──────────────────
            // Re-map [bpNorm .. 1.0] → [0 .. 1.0]; below threshold → 0.
            val v = hsv[2]
            hsv[2] = if (v < bpNorm) {
                0f
            } else {
                ((v - bpNorm) / (1f - bpNorm)).coerceIn(0f, 1f)
            }

            // ── Step 4: write back as COLOUR (not greyscale) ────────────────
            // Preserves hue so red text stays red, blue foil stays blue, etc.
            pixels[i] = Color.HSVToColor(hsv)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

}