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
        const val EXTRA_RESOLVED_NAME = "extra_resolved_name"
        const val EXTRA_RESOLVED_ID   = "extra_resolved_id"
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
    private var resolvedMedicineName = ""
    private var resolvedMedicineId = ""

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

        // 1. Image Passed to Gemma
        showStep(container, "📷 Image Passed to Gemma 3n", original.copy(Bitmap.Config.ARGB_8888, false))
        delay(200)

        // Show the contrast-filtered image if available
        val filteredBmp = BitmapHolder.filteredBitmap
        if (filteredBmp != null) {
            showStep(container, "✨ Contrast Filtered (BP70 + Sat75%)", filteredBmp.copy(Bitmap.Config.ARGB_8888, false))
            delay(200)
        }

        // Extract Gemma OCR results
        val squareOcr = intent.getStringExtra(EXTRA_PRIMARY_OCR)?.trim().orEmpty()
        val wideOcr = intent.getStringExtra(EXTRA_WIDE_OCR)?.trim().orEmpty()

        // 2. Gemma 3n Output
        showTextStep(container, "🤖 Gemma 3n Output Text", squareOcr.ifBlank { "(no text detected)" })
        delay(200)

        // Hide loader
        findViewById<ProgressBar>(R.id.loader).visibility = View.GONE

        // Load medicine DB
        withContext(Dispatchers.IO) { MedicineRepository.loadIfNeeded(this@ImageProcessingActivity) }

        resultPrimaryOcr = squareOcr
        resultWideOcr    = wideOcr

        showTextPipeline(container, squareOcr, wideOcr)
        showMatchResults(container, squareOcr, wideOcr)

        // Store the resolved medicine details to return them in the activity result
        val primary          = squareOcr.ifBlank { wideOcr }
        val correctedPrimary = NumericOcrCorrector.correct(primary)
        val correctedWide    = NumericOcrCorrector.correct(wideOcr)
        val blacklist        = intent.getStringArrayListExtra(EXTRA_BLACKLIST)?.toSet() ?: emptySet()
        val resolved         = MedicineNameResolver.resolveForScan(correctedPrimary, correctedWide, blacklist, 5)
        resolved.medicine?.let {
            resolvedMedicineName = it.name
            resolvedMedicineId = it.id
        }

        val hasText = squareOcr.length >= 3
        showMessage(if (hasText) "✅ Debug view — tap Close" else "Gemma returned no text — tap Close")
        findViewById<Button>(R.id.btnClose).visibility = View.VISIBLE
    }

    // ── Text pipeline steps ───────────────────────────────────────────────────

    private fun showTextPipeline(container: LinearLayout, squareOcr: String, wideOcr: String) {
        if (wideOcr.isNotBlank() && wideOcr != squareOcr) {
            showTextStep(container, "📝 Alternative OCR result", wideOcr)
        }
        val numericCorrected = NumericOcrCorrector.correct(squareOcr)
        if (numericCorrected != squareOcr) {
            showTextStep(container, "🔢 Numeric Corrected (G→6, S→5, O→0…)", numericCorrected.ifBlank { "(empty)" })
        }
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

        // Extract and display all candidate queries that the resolver evaluated
        val queries = (MedicineNameResolver.buildSearchQueries(correctedPrimary) +
                       (correctedWide.takeIf { it.isNotBlank() }?.let { MedicineNameResolver.buildSearchQueries(it) } ?: emptyList()))
                      .distinct()
        
        showTextStep(container, "🔎 Candidate Queries Evaluated", queries.joinToString(", "))

        val body = buildString {
            val top = resolved.medicine
            if (top != null) {
                append("Top Match: ${top.name}\n")
                val topMatch = resolved.alternatives.firstOrNull()
                if (topMatch != null) {
                    val isHashMap = topMatch.explanation == "EXACT_HASHMAP_MATCH"
                    val isSemantic = topMatch.explanation.contains("SEMANTIC_SIM")
                    val method = when {
                        isHashMap -> "⚡ HashMap Exact Match"
                        isSemantic -> "🧠 Semantic RAG (Vector) Match"
                        else -> "🔍 Fuzzy Match"
                    }
                    append("Match Method: $method\n")
                }
                append("Score: ${resolved.score.toInt()}%\n")
                append("Query Used: ${resolved.searchQuery}\n")
                
                if (topMatch != null && topMatch.explanation.isNotBlank() && topMatch.explanation != "EXACT_HASHMAP_MATCH") {
                    append("Score Breakdown: ${topMatch.explanation}\n")
                }
                
                append(if (MedicineNameResolver.shouldAutoPick(resolved))
                    "\nStatus: Auto-select confirmed (gap is wide enough)"
                else
                    "\nStatus: Tap Close — pick from suggestions if needed")
            } else {
                append("No match in your medicine list\n")
                append("Queries Tried: ${queries.joinToString(", ")}")
            }

            val alternates = resolved.alternatives.drop(1).take(3)
            if (alternates.isNotEmpty()) {
                append("\n\nOther Candidates Checked:")
                alternates.forEachIndexed { i, m ->
                    val isHashMap = m.explanation == "EXACT_HASHMAP_MATCH"
                    val isSemantic = m.explanation.contains("SEMANTIC_SIM")
                    val method = when {
                        isHashMap -> "HashMap Exact"
                        isSemantic -> "Semantic"
                        else -> "Fuzzy"
                    }
                    append("\n  ${i + 2}. ${m.medicine.name} (${m.score.toInt()}%) - $method")
                    if (m.explanation.isNotBlank() && !isHashMap) {
                        append("\n     Breakdown: ${m.explanation}")
                    }
                }
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
                if (resolvedMedicineName.isNotBlank()) {
                    putExtra(EXTRA_RESOLVED_NAME, resolvedMedicineName)
                    putExtra(EXTRA_RESOLVED_ID, resolvedMedicineId)
                }
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
