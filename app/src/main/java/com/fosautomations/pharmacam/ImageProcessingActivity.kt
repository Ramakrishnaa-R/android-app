package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug screen — shows the BP70 + Saturation 75% filter result and OCR output.
 */
class ImageProcessingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BLACKLIST   = "extra_blacklist"
        const val EXTRA_PRIMARY_OCR = "extra_primary_ocr"
        const val EXTRA_WIDE_OCR    = "extra_wide_ocr"
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.Main + kotlinx.coroutines.SupervisorJob() +
            CoroutineExceptionHandler { _, e ->
                Log.e("IMAGE_PROCESSING", "Pipeline failed", e)
                findViewById<Button>(R.id.btnClose)?.visibility = View.VISIBLE
                showMessage("Error: ${e.message ?: "processing failed"}")
            }
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var resultPrimaryOcr = ""
    private var resultWideOcr    = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_processing)

        val bitmap = BitmapHolder.bitmap
        if (bitmap == null) { finish(); return }

        findViewById<Button>(R.id.btnClose).apply {
            visibility = View.GONE
            setOnClickListener { finishWithResult() }
        }

        scope.launch { runPipeline(bitmap) }
    }

    // ── Pipeline ─────────────────────────────────────────────────────────────

    private suspend fun runPipeline(original: Bitmap) {
        val container = findViewById<LinearLayout>(R.id.stepsContainer)

        // 1. Original capture (scan zone)
        showStep(container, "📷 Original Capture (scan zone)", original.copy(Bitmap.Config.ARGB_8888, false))
        delay(200)

        // 2. BP70 + Sat75 filter (what OCR actually sees)
        val filteredSnap = BitmapHolder.filteredBitmap
        val filtered = if (filteredSnap != null && !filteredSnap.isRecycled) {
            filteredSnap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            val colourSrc = original.copy(Bitmap.Config.ARGB_8888, false)
            val up = withContext(Dispatchers.Default) { LabelOcrHelper.upscaleIfNeeded(colourSrc) }
            val ret = withContext(Dispatchers.Default) {
                ImageUtils.applyBlackPointSaturation(up, blackPoint = 70, saturation = 0.75f)
            }
            if (up !== colourSrc) colourSrc.recycle()
            up.recycle()
            ret
        }
        showStep(container, "🎯 BP70 + Sat75 Filter (sent to OCR)", filtered)
        val filterText = BitmapHolder.filterOcrTexts?.get("bp-sat") ?: ""
        showTextStep(container, "📝 OCR Text from Filter", filterText.ifBlank { "(no text)" })
        delay(200)

        // Hide loader
        findViewById<ProgressBar>(R.id.loader).visibility = View.GONE

        // Load medicine DB
        withContext(Dispatchers.IO) { MedicineRepository.loadIfNeeded(this@ImageProcessingActivity) }

        // Collect OCR results
        var squareOcr = intent.getStringExtra(EXTRA_PRIMARY_OCR)?.trim()
            ?: MainActivity.pendingOcrResult?.trim().orEmpty()
        var wideOcr = intent.getStringExtra(EXTRA_WIDE_OCR)?.trim()
            ?: MainActivity.pendingWideOcrText?.trim().orEmpty()

        var waits = 0
        while (squareOcr.length < 3 && wideOcr.length < 3 && waits < 15) {
            delay(200)
            squareOcr = MainActivity.pendingOcrResult?.trim().orEmpty()
            wideOcr   = MainActivity.pendingWideOcrText?.trim().orEmpty()
            waits++
        }
        if (squareOcr.length < 3) squareOcr = filterText
        if (wideOcr.length   < 3) wideOcr   = BitmapHolder.filterOcrTexts?.get("bp-sat").orEmpty()

        resultPrimaryOcr = squareOcr
        resultWideOcr    = wideOcr

        showTextPipeline(container, squareOcr, wideOcr)
        showMatchResults(container, squareOcr, wideOcr)

        val hasText = squareOcr.length >= 3 || wideOcr.length >= 3
        showMessage(if (hasText) "✅ Debug view — tap Close" else "Waiting for OCR… tap Close anyway")
        findViewById<Button>(R.id.btnClose).visibility = View.VISIBLE
    }

    // ── Text pipeline steps ───────────────────────────────────────────────────

    private fun showTextPipeline(container: LinearLayout, squareOcr: String, wideOcr: String) {
        showTextStep(container, "📝 OCR used for match (BP70+Sat75)", squareOcr.ifBlank { "(empty)" })
        if (wideOcr.isNotBlank() && wideOcr != squareOcr) {
            showTextStep(container, "📝 Alternative OCR result", wideOcr)
        }
        val numericCorrected = NumericOcrCorrector.correct(squareOcr)
        if (numericCorrected != squareOcr) {
            showTextStep(container, "🔢 Numeric Corrected (G→6, S→5, O→0…)", numericCorrected.ifBlank { "(empty)" })
        }
        val charFixed = numericCorrected.uppercase()
            .map { ch -> CHAR_FIXES[ch] ?: ch }.joinToString("")
        showTextStep(container, "🔤 Char Fixed (\$→S, 0→O, 2→Z…)", charFixed.ifBlank { "(empty)" })
        val normalized = Matcher.normalize(numericCorrected)
        showTextStep(container, "📋 Normalized (matcher)", normalized.ifBlank { "(empty)" })
        val searchQuery = resolveDisplayQuery(numericCorrected, wideOcr)
        showTextStep(container, "🔎 Search query (resolver)", searchQuery.ifBlank { "(empty)" })
        val matcherDebug = Matcher.debugInput(searchQuery.ifBlank { numericCorrected })
        showTextStep(container, "✅ Sent to Matcher", matcherDebug)
        scrollToBottom()
    }

    private fun resolveDisplayQuery(squareOcr: String, wideOcr: String): String {
        if (squareOcr.length < 3 && wideOcr.length < 3) return ""
        val primary          = squareOcr.ifBlank { wideOcr }
        val correctedPrimary = NumericOcrCorrector.correct(primary)
        val correctedWide    = NumericOcrCorrector.correct(wideOcr)
        val blacklist        = intent.getStringArrayListExtra(EXTRA_BLACKLIST)?.toSet() ?: emptySet()
        return if (MedicineRepository.isReady()) {
            MedicineNameResolver.resolveForScan(correctedPrimary, correctedWide, blacklist, 5).searchQuery
        } else {
            MedicineNameResolver.buildSearchQuery(correctedPrimary)
                .ifBlank { MedicineNameResolver.buildSearchQuery(correctedWide) }
        }
    }

    private fun showMatchResults(container: LinearLayout, squareOcr: String, wideOcr: String) {
        if (squareOcr.length < 3 && wideOcr.length < 3) {
            showHighlightStep(container, "💊 Match (medicines.json)", "(no OCR from camera yet)"); return
        }
        if (!MedicineRepository.isReady()) {
            showHighlightStep(container, "💊 Match (medicines.json)", "Medicine list not loaded"); return
        }
        val primary          = squareOcr.ifBlank { wideOcr }
        val correctedPrimary = NumericOcrCorrector.correct(primary)
        val correctedWide    = NumericOcrCorrector.correct(wideOcr)
        val blacklist        = intent.getStringArrayListExtra(EXTRA_BLACKLIST)?.toSet() ?: emptySet()
        val resolved         = MedicineNameResolver.resolveForScan(correctedPrimary, correctedWide, blacklist, 5)
        val query            = resolved.searchQuery.ifBlank { MedicineNameResolver.buildSearchQuery(correctedPrimary) }

        val body = buildString {
            val top = resolved.medicine
            if (top != null) {
                append(top.name)
                append("\n\nScore: ${resolved.score.toInt()}%")
                append("\nQuery: $query")
                append(if (MedicineNameResolver.shouldAutoPick(resolved))
                    "\n\nWill auto-select when you tap Close"
                else
                    "\n\nTap Close — pick from suggestions if needed")
            } else {
                append("No match in your medicine list")
                append("\n\nQuery tried: $query")
            }
            resolved.alternatives.drop(1).take(3).forEachIndexed { i, m ->
                append("\n  ${i + 2}. ${m.medicine.name} (${m.score.toInt()}%)")
            }
        }
        showHighlightStep(container, "💊 Match (medicines.json)", body.trim())
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun showStep(container: LinearLayout, label: String, bitmap: Bitmap) {
        val stepView = layoutInflater.inflate(R.layout.item_processing_step, container, false)
        stepView.findViewById<TextView>(R.id.stepLabel).text = label
        stepView.findViewById<View>(R.id.imageContainer).visibility = View.VISIBLE
        stepView.findViewById<ImageView>(R.id.stepImage).setImageBitmap(bitmap)
        container.addView(stepView)
        scrollToBottom()
    }

    private fun showTextStep(container: LinearLayout, label: String, text: String) {
        val stepView = layoutInflater.inflate(R.layout.item_processing_step, container, false)
        stepView.findViewById<TextView>(R.id.stepLabel).text = label
        stepView.findViewById<View>(R.id.imageContainer).visibility = View.GONE
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            textSize = 13f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
        }
        (stepView as ViewGroup).addView(tv)
        container.addView(stepView)
        scrollToBottom()
    }

    private fun showHighlightStep(container: LinearLayout, label: String, text: String) {
        val stepView = layoutInflater.inflate(R.layout.item_processing_step, container, false)
        stepView.findViewById<TextView>(R.id.stepLabel).text = label
        stepView.findViewById<View>(R.id.imageContainer).visibility = View.GONE
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor("#A5D6A7"))
            textSize = 15f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(android.graphics.Color.parseColor("#1B5E20"))
        }
        (stepView as ViewGroup).addView(tv)
        container.addView(stepView)
        scrollToBottom()
    }

    private fun showMessage(msg: String) {
        val container = findViewById<LinearLayout>(R.id.stepsContainer)
        val tv = TextView(this).apply {
            text = msg
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }
        container.addView(tv)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        findViewById<ScrollView>(R.id.scrollView).post {
            findViewById<ScrollView>(R.id.scrollView).fullScroll(View.FOCUS_DOWN)
        }
    }

    // ── Result & lifecycle ───────────────────────────────────────────────────

    private fun finishWithResult() {
        setResult(
            RESULT_OK,
            android.content.Intent().apply {
                putExtra(EXTRA_PRIMARY_OCR,
                    resultPrimaryOcr.ifBlank {
                        intent.getStringExtra(EXTRA_PRIMARY_OCR)
                            ?: MainActivity.pendingOcrResult.orEmpty()
                    })
                putExtra(EXTRA_WIDE_OCR,
                    resultWideOcr.ifBlank {
                        intent.getStringExtra(EXTRA_WIDE_OCR)
                            ?: MainActivity.pendingWideOcrText.orEmpty()
                    })
            }
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        BitmapHolder.bitmap = null
        BitmapHolder.wideBitmap = null
        BitmapHolder.clearPipelineSnapshots()
    }

    @Deprecated("Deprecated in Android API; keeps hardware Back behavior aligned with Close.")
    override fun onBackPressed() {
        finishWithResult()
    }
}
