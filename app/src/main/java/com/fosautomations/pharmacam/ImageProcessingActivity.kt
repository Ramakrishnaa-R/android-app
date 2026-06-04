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
 * Rhohit debug screen — image pipeline steps only.
 * Matching uses OCR from [MainActivity] shutter (LabelOcrHelper); this screen never re-OCRs or overwrites it.
 */
class ImageProcessingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BLACKLIST = "extra_blacklist"
        const val EXTRA_PRIMARY_OCR = "extra_primary_ocr"
        const val EXTRA_WIDE_OCR = "extra_wide_ocr"
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.Main + kotlinx.coroutines.SupervisorJob() +
            CoroutineExceptionHandler { _, e ->
                Log.e("IMAGE_PROCESSING", "Pipeline failed", e)
                findViewById<Button>(R.id.btnClose)?.visibility = View.VISIBLE
                showmessage("Error: ${e.message ?: "processing failed"}")
            }
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var resultPrimaryOcr = ""
    private var resultWideOcr = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_processing)

        val bitmap = BitmapHolder.bitmap
        if (bitmap == null) {
            finish()
            return
        }

        findViewById<Button>(R.id.btnClose).apply {
            visibility = View.GONE
            setOnClickListener { finishWithResult() }
        }

        scope.launch {
            runPipeline(bitmap)
        }
    }

    private suspend fun runPipeline(original: Bitmap) {
        val container = findViewById<LinearLayout>(R.id.stepsContainer)

        showStep(container, "📷 Original Capture (1:1)", original)
        delay(500)

        val grayscale = withContext(Dispatchers.Default) {
            ImageUtils.toGrayscale(original)
        }
        showStep(container, "🔲 Grayscale", grayscale)
        delay(500)

        val noGlare = withContext(Dispatchers.Default) {
            ImageUtils.removeSpecularHighlights(grayscale)
        }
        if (grayscale !== noGlare) grayscale.recycle()
        showStep(container, "✨ Glare Removed", noGlare)
        delay(500)

        val thresholded = withContext(Dispatchers.Default) {
            ImageUtils.adaptiveThreshold(noGlare)
        }
        showStep(container, "⬛ Adaptive Threshold", thresholded)
        delay(500)

        val binarized = withContext(Dispatchers.Default) {
            ImageUtils.preprocessForOCR(noGlare)
        }
        showStep(container, "🔤 Binarized (debug view)", binarized)
        delay(500)

        findViewById<ProgressBar>(R.id.loader).visibility = View.GONE

        withContext(Dispatchers.IO) {
            MedicineRepository.loadIfNeeded(this@ImageProcessingActivity)
        }

        // OCR from MainActivity shutter — not re-run here (Rhohit re-OCR was hurting accuracy).
        var squareOcr = intent.getStringExtra(EXTRA_PRIMARY_OCR)?.trim()
            ?: MainActivity.pendingOcrResult?.trim().orEmpty()
        var wideOcr = intent.getStringExtra(EXTRA_WIDE_OCR)?.trim()
            ?: MainActivity.pendingWideOcrText?.trim().orEmpty()
        var waits = 0
        while (squareOcr.length < 3 && wideOcr.length < 3 && waits < 15) {
            delay(200)
            squareOcr = MainActivity.pendingOcrResult?.trim().orEmpty()
            wideOcr = MainActivity.pendingWideOcrText?.trim().orEmpty()
            waits++
        }

        if (squareOcr.length < 3) {
            squareOcr = recognizeDebugBitmap(original)
        }
        if (wideOcr.length < 3) {
            BitmapHolder.wideBitmap?.let { wideBitmap ->
                wideOcr = recognizeDebugBitmap(wideBitmap)
            }
        }
        resultPrimaryOcr = squareOcr
        resultWideOcr = wideOcr

        showTextPipeline(container, squareOcr, wideOcr)
        showMatchResults(container, squareOcr, wideOcr)

        if (squareOcr.length >= 3 || wideOcr.length >= 3) {
            showmessage("✅ Debug view — tap Close (match uses camera OCR, not this screen)")
            findViewById<Button>(R.id.btnClose).visibility = View.VISIBLE
        } else {
            showmessage("Waiting for OCR from camera… tap Close anyway")
            findViewById<Button>(R.id.btnClose).visibility = View.VISIBLE
        }

        if (binarized !== noGlare) binarized.recycle()
        noGlare.recycle()
    }

    private suspend fun recognizeDebugBitmap(bitmap: Bitmap): String {
        if (bitmap.isRecycled) return ""
        return try {
            val cropCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val rhohitPrepared = withContext(Dispatchers.Default) {
                ImageUtils.removeSpecularHighlights(ImageUtils.toGrayscale(cropCopy))
            }
            cropCopy.recycle()
            val prepared = LabelOcrHelper.prepareForOcr(rhohitPrepared)
            if (rhohitPrepared !== prepared) rhohitPrepared.recycle()

            val primary = recognizePrepared(prepared)
            if (!LabelOcrHelper.needsFallback(primary)) {
                prepared.recycle()
                return primary.fullText.ifBlank { primary.matchText }
            }

            val binarized = LabelOcrHelper.preprocessBinarized(prepared)
            prepared.recycle()
            val secondary = recognizePrepared(binarized)
            binarized.recycle()
            val best = LabelOcrHelper.pickBetter(primary, secondary)
            best.fullText.ifBlank { best.matchText }
        } catch (e: Exception) {
            Log.e("IMAGE_PROCESSING", "Fallback OCR failed", e)
            ""
        }
    }

    private suspend fun recognizePrepared(bitmap: Bitmap): LabelOcrHelper.OcrResult =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(LabelOcrHelper.extractBestText(visionText))
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private fun showTextPipeline(container: LinearLayout, squareOcr: String, wideOcr: String) {
        showTextStep(container, "📝 OCR used for match (1:1 / pickBest)", squareOcr.ifBlank { "(empty)" })
        if (wideOcr.isNotBlank()) {
            showTextStep(container, "📝 Wide crop hint (3:1)", wideOcr)
        }

        val charFixed = squareOcr.uppercase()
            .map { ch -> CHAR_FIXES[ch] ?: ch }
            .joinToString("")
        showTextStep(container, "🔤 Char Fixed (\$→S, 0→O, 2→Z …)", charFixed.ifBlank { "(empty)" })

        val normalized = Matcher.normalize(squareOcr)
        showTextStep(container, "📋 Normalized (matcher)", normalized.ifBlank { "(empty)" })

        val searchQuery = resolveDisplayQuery(squareOcr, wideOcr)
        showTextStep(container, "🔎 Search query (resolver)", searchQuery.ifBlank { "(empty)" })

        val matcherDebug = Matcher.debugInput(searchQuery.ifBlank { squareOcr })
        showTextStep(container, "✅ Sent to Matcher", matcherDebug)

        scrollToBottom()
    }

    private fun resolveDisplayQuery(squareOcr: String, wideOcr: String): String {
        if (squareOcr.length < 3 && wideOcr.length < 3) return ""
        val primary = squareOcr.ifBlank { wideOcr }
        val blacklist = intent.getStringArrayListExtra(EXTRA_BLACKLIST)?.toSet() ?: emptySet()
        return if (MedicineRepository.isReady()) {
            MedicineNameResolver.resolveForScan(primary, wideOcr, blacklist, 5).searchQuery
        } else {
            MedicineNameResolver.buildSearchQuery(primary)
                .ifBlank { MedicineNameResolver.buildSearchQuery(wideOcr) }
        }
    }

    private fun showMatchResults(container: LinearLayout, squareOcr: String, wideOcr: String) {
        if (squareOcr.length < 3 && wideOcr.length < 3) {
            showHighlightStep(container, "💊 Match (medicines.json)", "(no OCR from camera yet)")
            return
        }
        if (!MedicineRepository.isReady()) {
            showHighlightStep(
                container,
                "💊 Match (medicines.json)",
                "Medicine list not loaded"
            )
            return
        }

        val primary = squareOcr.ifBlank { wideOcr }
        val blacklist = intent.getStringArrayListExtra(EXTRA_BLACKLIST)?.toSet() ?: emptySet()
        val resolved = MedicineNameResolver.resolveForScan(primary, wideOcr, blacklist, 5)
        val query = resolved.searchQuery.ifBlank {
            MedicineNameResolver.buildSearchQuery(primary)
        }

        val body = buildString {
            val top = resolved.medicine
            if (top != null) {
                append(top.name)
                append("\n\nScore: ${resolved.score.toInt()}%")
                append("\nQuery: $query")
                if (MedicineNameResolver.shouldAutoPick(resolved)) {
                    append("\n\nWill auto-select when you tap Close")
                } else {
                    append("\n\nTap Close — pick from suggestions if needed")
                }
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

    private fun showTextStep(container: LinearLayout, label: String, text: String) {
        val stepView = layoutInflater.inflate(R.layout.item_processing_step, container, false)
        stepView.findViewById<TextView>(R.id.stepLabel).text = label
        stepView.findViewById<ImageView>(R.id.stepImage).visibility = View.GONE
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
        stepView.findViewById<ImageView>(R.id.stepImage).visibility = View.GONE
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

    private fun showmessage(msg: String) {
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

    private fun showStep(container: LinearLayout, label: String, bitmap: Bitmap) {
        val stepView = layoutInflater.inflate(R.layout.item_processing_step, container, false)
        stepView.findViewById<TextView>(R.id.stepLabel).text = label
        stepView.findViewById<ImageView>(R.id.stepImage).setImageBitmap(bitmap)
        container.addView(stepView)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        findViewById<ScrollView>(R.id.scrollView).post {
            findViewById<ScrollView>(R.id.scrollView).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun finishWithResult() {
        setResult(
            RESULT_OK,
            android.content.Intent().apply {
                putExtra(
                    EXTRA_PRIMARY_OCR,
                    resultPrimaryOcr.ifBlank {
                        intent.getStringExtra(EXTRA_PRIMARY_OCR)
                            ?: MainActivity.pendingOcrResult.orEmpty()
                    }
                )
                putExtra(
                    EXTRA_WIDE_OCR,
                    resultWideOcr.ifBlank {
                        intent.getStringExtra(EXTRA_WIDE_OCR)
                            ?: MainActivity.pendingWideOcrText.orEmpty()
                    }
                )
            }
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        BitmapHolder.bitmap = null
        BitmapHolder.wideBitmap = null
    }

    @Deprecated("Deprecated in Android API; keeps hardware Back behavior aligned with Close.")
    override fun onBackPressed() {
        finishWithResult()
    }
}
