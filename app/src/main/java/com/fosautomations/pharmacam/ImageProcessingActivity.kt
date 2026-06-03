package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Rhohit debug screen: image pipeline steps + OCR + string normalization before matcher.
 */
class ImageProcessingActivity : AppCompatActivity() {

    private val scope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
            setOnClickListener { finish() }
        }

        scope.launch {
            runPipeline(bitmap)
        }
    }

    private suspend fun runPipeline(original: Bitmap) {
        val container = findViewById<LinearLayout>(R.id.stepsContainer)

        showStep(container, "📷 Original Capture (1:1)", original)
        saveDebugStage(original, "debug_original")
        delay(600)

        val grayscale = withContext(Dispatchers.Default) {
            ImageUtils.toGrayscale(original)
        }
        showStep(container, "🔲 Grayscale", grayscale)
        saveDebugStage(grayscale, "debug_grayscale")
        delay(600)

        val noGlare = withContext(Dispatchers.Default) {
            ImageUtils.removeSpecularHighlights(grayscale)
        }
        if (grayscale !== noGlare) grayscale.recycle()
        showStep(container, "✨ Glare Removed", noGlare)
        saveDebugStage(noGlare, "debug_glare_removed")
        delay(600)

        val thresholded = withContext(Dispatchers.Default) {
            ImageUtils.adaptiveThreshold(noGlare)
        }
        showStep(container, "⬛ Adaptive Threshold", thresholded)
        saveDebugStage(thresholded, "debug_adaptive_threshold")
        delay(600)

        val forOcr = withContext(Dispatchers.Default) {
            ImageUtils.preprocessForOCR(noGlare)
        }
        showStep(container, "🔤 Binarized — Sent to OCR", forOcr)
        saveDebugStage(forOcr, "debug_binarized_ocr")
        delay(600)

        findViewById<ProgressBar>(R.id.loader).visibility = View.GONE

        runOcrAndShowTextPipeline(container, forOcr)
        if (forOcr !== noGlare) forOcr.recycle()
        noGlare.recycle()
    }

    private suspend fun runOcrAndShowTextPipeline(container: LinearLayout, ocrBitmap: Bitmap) {
        val fullText = withContext(Dispatchers.Default) {
            runMlKitOcr(ocrBitmap)
        }

        android.util.Log.d("IMAGE_PROCESSING", "OCR TEXT: $fullText")

        showTextPipeline(container, fullText)

        // Wide 3:1 crop may still be processing in MainActivity
        repeat(3) {
            delay(500)
            val wide = MainActivity.pendingWideOcrText?.trim().orEmpty()
            if (wide.length >= 3) {
                showTextStep(container, "📐 Wide crop (3:1) OCR", wide)
                scrollToBottom()
                return@repeat
            }
        }

        if (fullText.length >= 3) {
            MainActivity.pendingOcrResult = fullText
            showmessage("✅ OCR Done — tap Close to continue")
            findViewById<Button>(R.id.btnClose).visibility = View.VISIBLE
        } else {
            showmessage("Nothing readable — move closer")
            findViewById<Button>(R.id.btnClose).visibility = View.VISIBLE
        }
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    val text = visionText.textBlocks
                        .filter { block -> block.text.any { it.code in 65..122 } }
                        .sortedByDescending { block ->
                            block.lines.maxOfOrNull { it.boundingBox?.height() ?: 0 } ?: 0
                        }
                        .take(5)
                        .joinToString(" ") { it.text.replace("\n", " ") }
                        .trim()
                    if (cont.isActive) cont.resume(text)
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("IMAGE_PROCESSING", "OCR failed", e)
                    if (cont.isActive) cont.resume("")
                }
        }

    /** Rhohit text-debug steps (same as his branch). */
    private fun showTextPipeline(container: LinearLayout, rawOcr: String) {
        showTextStep(container, "📝 Raw OCR", rawOcr)

        val charFixed = rawOcr.uppercase()
            .map { ch -> CHAR_FIXES[ch] ?: ch }
            .joinToString("")
        showTextStep(container, "🔤 Char Fixed (\$→S, 0→O, 2→Z …)", charFixed)

        val normalized = Matcher.normalize(rawOcr)
        showTextStep(container, "📋 Normalized (matcher)", normalized)

        val searchQuery = MedicineNameResolver.buildSearchQuery(rawOcr)
        showTextStep(container, "🔎 Search query (resolver)", searchQuery.ifBlank { "(empty)" })

        val matcherDebug = Matcher.debugInput(rawOcr)
        showTextStep(container, "✅ Sent to Matcher", matcherDebug)

        scrollToBottom()
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

    private fun saveDebugStage(bitmap: Bitmap, label: String) {
        if (!ScanDebugImageSaver.ENABLED || bitmap.isRecycled) return
        ScanDebugImageSaver.saveCapture(this, bitmap, label)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        recognizer.close()
        BitmapHolder.bitmap = null
    }
}
