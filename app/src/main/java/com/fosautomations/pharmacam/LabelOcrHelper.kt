package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Label OCR: crop, enhance, upscale, line scoring, optional binarized fallback pass.
 * No image classifier — text extraction only.
 */
object LabelOcrHelper {

    private const val TAG = "TOM_DEBUG"

    /** ML Kit reads small text better above this width. */
    private const val MIN_OCR_WIDTH = 800

    private const val CROP_LEFT_FRAC = 0.14f
    private const val CROP_WIDTH_FRAC = 0.72f
    /** Narrow band — good for horizontal brand strips. */
    private const val CROP_TOP_FRAC = 0.28f
    private const val CROP_HEIGHT_FRAC = 0.40f

    /** Large center crop — best for boxes (ALKOF COFGELS) in the scan frame. */
    private const val CENTER_LEFT_FRAC = 0.06f
    private const val CENTER_TOP_FRAC = 0.10f
    private const val CENTER_WIDTH_FRAC = 0.88f
    private const val CENTER_HEIGHT_FRAC = 0.80f

    private val CHAR_FIXES = mapOf('$' to 'S', '@' to 'A', '!' to 'I', '|' to 'I')

    private val LINE_DROP_PATTERNS = listOf(
        Regex("""\b(MFG|MANUFACTUR|MARKETED|BATCH|EXP|EXPIRY|MRP|LICENSE|LICENCE|REG\.?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(KEEP|STORE|TEMPERATURE|REFRIGERAT|CAUTION|WARNING|CONTRAIND)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bWWW\.|HTTP|\.COM\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(STORAGE|DIRECTIONS?|DOSAGE|COMPOSITION|INGREDIENT)\b""", RegexOption.IGNORE_CASE)
    )

    private val FORM_WORD = Regex(
        """\b(\d+\s*)?(MG|MCG|GM|G|ML|M\s*L|TAB|TABS|TABLET|CAP|CAPS|SYP|SUSP|INJ)\b""",
        RegexOption.IGNORE_CASE
    )

    private val STRENGTH_PATTERN = Regex("""\b\d+(\.\d+)?\s*(MG|MCG|GM|G|ML)\b""", RegexOption.IGNORE_CASE)

    /** Generic / ingredient lines — prefer short brand names like ALKAZAR over these. */
    private val INGREDIENT_WORDS = setOf(
        "DISODIUM", "CITRATE", "HYDROGEN", "PHOSPHATE", "SULFATE", "CHLORIDE",
        "PARACETAMOL", "PAROCETAMOL", "PUROCETOMOL", "ACETAMINOPHEN",
        "AMOXICILLIN", "METFORMIN", "OMEPRAZOLE", "COMPOSITION",
        "INGREDIENT", "SOLUTION", "SYRUP", "FLAVOURED", "FLAVORED", "ALKALIZER",
        "HYDROBROMIDE", "MALEATE", "PHENYLEPHRINE", "GELATIN", "COUGH", "RELIEF",
        "ACTING", "DEXTROMETHORPHAN", "CHLORPHENIRAMINE", "SOFT", "CAPSULES",
        "TABLETS", "TABLET", "TOBLETS", "TOBLET", "CAPSULE"
    )

    /** Brand + strength on strip, e.g. Dolo-650, Augmentin625 */
    private val BRAND_STRENGTH_LINE = Regex(
        """\b[A-Z][A-Z0-9]{1,10}[\s\-]?\d{2,4}\b""",
        RegexOption.IGNORE_CASE
    )

    private val INGREDIENT_LINE = Regex(
        """PARACETAMOL|PAROCETAMOL|PUROCETOMOL|FUROCETOMOL|FUROCETAMOL|ACETAMINOPHEN|""" +
            """TABLETS?|TAHLES|TOBLETS?|TOBLETY?|CAPSULES|CETAMOL|ETAMOL""",
        RegexOption.IGNORE_CASE
    )

    /** OCR often reads Dolo/Dole/olo-650 — all should count as brand. */
    private val DOLO_BRAND_LINE = Regex(
        """\bD[O0][L1][EO0][\s\-]?650\b|\bOLO[\s\-]?650\b|\bD[O0]LO[\s\-]?650\b""",
        RegexOption.IGNORE_CASE
    )

    private val BRAND_LIKE = Regex("""^[A-Z][A-Z0-9\-]{4,14}$""")

    private val COFGELS_REPLACEMENT = Regex("""\bCOFGEL[S]?\b""", RegexOption.IGNORE_CASE)
    private val ALKOF_REPLACEMENT = Regex("""\bALK0F\b""", RegexOption.IGNORE_CASE)
    private val DOLO650_REPLACEMENT = Regex("""\bD[O0][L1][EO0][\s\-]?650\b""", RegexOption.IGNORE_CASE)
    private val OLO650_REPLACEMENT = Regex("""\bOLO[\s\-]?650\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val DOLO_WORD = Regex("""\bDOLO\b""", RegexOption.IGNORE_CASE)

    data class OcrResult(
        val matchText: String,
        val fullText: String,
        val topLines: List<String>,
        val lineScore: Int
    )

    private data class ScoredLine(val line: String, val score: Int)

    /** Wide strip: width = 3 × height (horizontal brand lines). */
    private const val WIDE_ASPECT_WIDTH = 3
    private const val WIDE_ASPECT_HEIGHT = 1

    /**
     * Centered 1:1 square — matches the on-screen scan box.
     * Primary OCR crop for boxes, blister strips, bottles in frame.
     */
    fun cropCenterSquare(bitmap: Bitmap): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    /**
     * Centered 3:1 wide rectangle.
     * Secondary crop for horizontal brand text (ALKOF COFGELS, etc.).
     */
    fun cropCenterWide3x1(bitmap: Bitmap): Bitmap {
        val cropH = min(bitmap.height, bitmap.width / WIDE_ASPECT_WIDTH).coerceAtLeast(1)
        val cropW = (cropH * WIDE_ASPECT_WIDTH).coerceAtMost(bitmap.width)
        val left = (bitmap.width - cropW) / 2
        val top = (bitmap.height - cropH) / 2
        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }

    /**
     * Centered 3:2 aspect ratio crop.
     * Single crop for multi-filter OCR pipeline (replaces 1:1 and 3:1 two-crop approach).
     * Aspect ratio 3:2 provides a balanced view for package labels and medicine names.
     */
    fun cropCenter3x2(bitmap: Bitmap): Bitmap {
        val maxH = bitmap.height
        val maxW = bitmap.width
        
        // Calculate crop dimensions maintaining 3:2 aspect ratio
        // Try to fit height first, then constrain width
        var cropH = maxH
        var cropW = (cropH * 3 / 2).toInt()
        
        // If width exceeds image width, fit width first
        if (cropW > maxW) {
            cropW = maxW
            cropH = (cropW * 2 / 3).toInt()
        }
        
        cropH = cropH.coerceAtLeast(1)
        cropW = cropW.coerceAtLeast(1)
        
        val left = (maxW - cropW) / 2
        val top = (maxH - cropH) / 2
        return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
    }

    /** Brand band on full capture (fractional crop — optional extra pass). */
    fun cropBrandRegion(bitmap: Bitmap): Bitmap =
        cropFraction(bitmap, CROP_LEFT_FRAC, CROP_TOP_FRAC, CROP_WIDTH_FRAC, CROP_HEIGHT_FRAC)

    /** Large center fractional crop (optional extra pass). */
    fun cropCenterRegion(bitmap: Bitmap): Bitmap =
        cropFraction(bitmap, CENTER_LEFT_FRAC, CENTER_TOP_FRAC, CENTER_WIDTH_FRAC, CENTER_HEIGHT_FRAC)

    /** Pixel bounds for preview quality checks (same as [cropCenterSquare]). */
    fun centerSquareRoi(width: Int, height: Int): IntArray {
        val side = min(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        return intArrayOf(left, top, left + side, top + side)
    }

    /** Pixel bounds for 3:1 aspect ratio scan box quality checks. */
    fun centerWide3x1Roi(width: Int, height: Int): IntArray {
        val cropH = min(height, width / 3).coerceAtLeast(1)
        val cropW = (cropH * 3).coerceAtMost(width)
        val left = (width - cropW) / 2
        val top = (height - cropH) / 2
        return intArrayOf(left, top, left + cropW, top + cropH)
    }

    private fun cropFraction(
        bitmap: Bitmap,
        leftFrac: Float,
        topFrac: Float,
        widthFrac: Float,
        heightFrac: Float
    ): Bitmap {
        val left = (bitmap.width * leftFrac).toInt()
        val top = (bitmap.height * topFrac).toInt()
        val width = (bitmap.width * widthFrac).toInt().coerceAtLeast(1)
        val height = (bitmap.height * heightFrac).toInt().coerceAtLeast(1)
        val safeW = min(width, bitmap.width - left).coerceAtLeast(1)
        val safeH = min(height, bitmap.height - top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, left, top, safeW, safeH)
    }

    fun pickBest(vararg results: OcrResult): OcrResult =
        results.reduce { acc, next -> pickBetter(acc, next) }

    /** Upscale + mild contrast (preferred for ML Kit — not harsh binarization). */
    fun prepareForOcr(source: Bitmap): Bitmap {
        val scaled = upscaleIfNeeded(source)
        if (scaled !== source) source.recycle()
        val enhanced = enhanceContrast(scaled)
        if (enhanced !== scaled) scaled.recycle()
        return sharpenLight(enhanced)
    }

    /** Second pass when mild enhance yields weak lines. */
    fun preprocessBinarized(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) ushr 8
            val e = when {
                gray > 210 -> 255
                gray > 155 -> 235
                gray > 95 -> 170
                else -> 35
            }
            pixels[i] = (0xFF shl 24) or (e shl 16) or (e shl 8) or e
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun extractBestText(visionText: Text): OcrResult {
        val rawLines = mutableListOf<String>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val t = normalizeLine(line.text)
                if (t.length >= 2) rawLines.add(t)
            }
        }
        if (rawLines.isEmpty()) {
            val fallback = normalizeLine(visionText.text.replace("\n", " "))
            return OcrResult(
                matchText = fallback,
                fullText = fallback,
                topLines = listOf(fallback),
                lineScore = if (fallback.length >= 4) 10 else 0
            )
        }

        val scored = rawLines
            .map { ScoredLine(it, scoreLine(it)) }
            .sortedByDescending { it.score }

        scored.forEach { s ->
            val tag = if (s.score > 0) "KEEP" else "DROP"
            Log.d(TAG, "line score=${s.score} $tag: ${s.line}")
        }

        val bestLine = pickBrandLine(rawLines, scored)
        val matchText = bestLine?.ifBlank { null }
            ?: normalizeLine(visionText.text.replace("\n", " "))
        val fullText = rawLines.joinToString(" ")
        val totalScore = scored.firstOrNull()?.score ?: 0

        Log.i(TAG, "matchText='$matchText' | full=${fullText.take(120)}…")

        return OcrResult(
            matchText = matchText,
            fullText = fullText,
            topLines = listOfNotNull(bestLine),
            lineScore = totalScore
        )
    }

    fun needsFallback(result: OcrResult): Boolean =
        result.matchText.length < 4 || result.lineScore < 12

    fun pickBetter(a: OcrResult, b: OcrResult): OcrResult =
        when {
            b.lineScore > a.lineScore + 5 -> b
            a.lineScore > b.lineScore + 5 -> a
            b.matchText.length > a.matchText.length + 3 -> b
            else -> a
        }

    fun upscaleIfNeeded(source: Bitmap): Bitmap {
        if (source.width >= MIN_OCR_WIDTH) return source
        val scale = MIN_OCR_WIDTH.toFloat() / source.width
        val newW = MIN_OCR_WIDTH
        val newH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    private fun enhanceContrast(source: Bitmap): Bitmap {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(source.width, source.height, config)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = ColorMatrix(
            floatArrayOf(
                1.18f, 0f, 0f, 0f, -12f,
                0f, 1.18f, 0f, 0f, -12f,
                0f, 0f, 1.18f, 0f, -12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    /** Light 3×3 sharpen on luminance. */
    fun sharpenLight(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 3 || h < 3) return source

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        fun luma(p: Int) = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3

        val out = pixels.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = luma(pixels[y * w + x])
                val blur = (
                    luma(pixels[(y - 1) * w + x]) +
                        luma(pixels[(y + 1) * w + x]) +
                        luma(pixels[y * w + (x - 1)]) +
                        luma(pixels[y * w + (x + 1)])
                    ) / 4
                val sharp = (c + (c - blur)).coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (sharp shl 16) or (sharp shl 8) or sharp
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (result !== source) source.recycle()
        return result
    }

    private fun normalizeLine(text: String): String {
        var s = text.trim()
        for ((bad, good) in CHAR_FIXES) {
            s = s.replace(bad, good)
        }
        s = s.replace(COFGELS_REPLACEMENT, "COFGELS")
        s = s.replace(ALKOF_REPLACEMENT, "ALKOF")
        s = s.replace(DOLO650_REPLACEMENT, "DOLO-650")
        s = s.replace(OLO650_REPLACEMENT, "DOLO-650")
        return s.replace(SPACES, " ").trim()
    }

    /** Prefer Dolo-650-style lines over Paracetamol Tablets lines. */
    private fun pickBrandLine(
        rawLines: List<String>,
        scored: List<ScoredLine>
    ): String? {
        val brandCandidates = rawLines.filter { line ->
            val u = line.uppercase(Locale.ROOT)
            BRAND_STRENGTH_LINE.containsMatchIn(line) ||
                DOLO_BRAND_LINE.containsMatchIn(line) ||
                DOLO_WORD.containsMatchIn(line) ||
                (u.contains("650") && u.length <= 14 && u.contains("OLO")) ||
                (u.contains("ALKOF") && u.contains("COFGEL"))
        }
        if (brandCandidates.isNotEmpty()) {
            return brandCandidates.maxByOrNull { scoreLine(it) + brandLineBonus(it) }
        }
        return scored.firstOrNull { it.score > 0 }?.line
    }

    private fun isIngredientOnlyLine(upper: String): Boolean {
        if (DOLO_BRAND_LINE.containsMatchIn(upper) || BRAND_STRENGTH_LINE.containsMatchIn(upper)) {
            return false
        }
        if (upper.contains("DOLO") || (upper.contains("650") && upper.contains("OLO"))) {
            return false
        }
        return INGREDIENT_LINE.containsMatchIn(upper) ||
            (upper.contains("CETAMOL") && !upper.contains("DOLO"))
    }

    private fun brandLineBonus(line: String): Int {
        var bonus = 0
        if (BRAND_STRENGTH_LINE.containsMatchIn(line)) bonus += 120
        if (DOLO_BRAND_LINE.containsMatchIn(line)) bonus += 150
        if (DOLO_WORD.containsMatchIn(line)) bonus += 80
        if (isIngredientOnlyLine(line.uppercase(Locale.ROOT))) bonus -= 200
        return bonus
    }

    private fun scoreLine(line: String): Int {
        val upper = line.uppercase(Locale.ROOT)
        if (upper.length < 3) return 0
        if (LINE_DROP_PATTERNS.any { it.containsMatchIn(upper) }) return 0
        if (isIngredientOnlyLine(upper)) return 0
        if (upper.count { it.isLetter() } < 3) return 0

        val letters = upper.count { it.isLetter() }
        val digits = upper.count { it.isDigit() }
        if (digits > letters && letters < 4) return 0

        var score = min(upper.length, 40)

        val upperRatio = letters.toFloat() / max(1, upper.length)
        if (upperRatio > 0.75f) score += 18
        else if (upperRatio < 0.4f) score -= 15

        if (BRAND_STRENGTH_LINE.containsMatchIn(upper)) score += 90
        if (DOLO_WORD.containsMatchIn(upper)) score += 50
        if (STRENGTH_PATTERN.containsMatchIn(upper)) score += 20
        if (FORM_WORD.containsMatchIn(upper)) score += 5

        val words = upper.split(SPACES).filter { it.length >= 4 }
        val drugLike = words.count { w ->
            w.length >= 5 && w.any { it.isLetter() } && w.count { it.isDigit() } <= 2
        }
        score += drugLike * 10

        val ingredientHits = words.count { it in INGREDIENT_WORDS }
        score -= ingredientHits * 18

        val brandWord = words.firstOrNull { BRAND_LIKE.matches(it) && it !in INGREDIENT_WORDS }
        if (brandWord != null) score += 28

        if (words.size <= 3 && upper.length in 5..28) score += 15

        if (upper.contains("ALKOF") && upper.contains("COFGEL")) score += 40
        if (upper.contains("COFGEL") && upper.length <= 24) score += 25

        if (upper.length > 55) score -= 15
        if (upper.split(" ").size > 6) score -= 12

        return score.coerceAtLeast(0)
    }
}
