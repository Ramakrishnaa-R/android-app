package com.fosautomations.pharmacam

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*

class ImageProcessingActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_processing)

        val bitmap = BitmapHolder.bitmap
        if (bitmap == null) {
            finish()
            return
        }

        scope.launch {
            runPipeline(bitmap)
        }
    }

    private suspend fun runPipeline(original: Bitmap) {
        val container = findViewById<LinearLayout>(R.id.stepsContainer)

        // Step 1 — Original captured photo
        showStep(container, "📷 Original Capture", original)
        delay(800)

        // Step 2 — Grayscale
        val grayscale = withContext(Dispatchers.Default) {
            ImageUtils.toGrayscale(original)
        }
        showStep(container, "🔲 Grayscale", grayscale)
        delay(800)

        // Step 3 — Remove glare (skip adaptiveThreshold — hurts OCR on foil)
        val noGlare = withContext(Dispatchers.Default) {
            ImageUtils.removeSpecularHighlights(grayscale)
        }
        showStep(container, "✨ Glare Removed — Sent to OCR", noGlare)
        delay(800)

        // Hide loader
        findViewById<ProgressBar>(R.id.loader).visibility = View.GONE

        // Step 4 — Run OCR on noGlare image
        runOCR(noGlare)
    }

    private fun runOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.textBlocks
                    .filter { block ->
                        block.text.any { it.code in 65..122 } // latin only, skip Hindi
                    }
                    .sortedByDescending { block ->
                        block.lines.maxOfOrNull { it.boundingBox?.height() ?: 0 } ?: 0
                    }
                    .take(5)
                    .joinToString(" ") { it.text.replace("\n", " ") }
                    .trim()

                android.util.Log.d("IMAGE_PROCESSING", "OCR TEXT: $fullText")

                if (fullText.length >= 3) {
                    // Pass result back to MainActivity
                    MainActivity.pendingOcrResult = fullText
                    finish() // close this activity, MainActivity resumes
                } else {
                    runOnUiThread {
                        showmessage("Nothing readable — move closer")
                    }
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("IMAGE_PROCESSING", "OCR failed", e)
                runOnUiThread {
                    showmessage("OCR failed — try again")
                }
            }
            .addOnCompleteListener { _ ->
                bitmap.recycle()
            }
    }

    private fun showmessage(msg: String) {
        val container = findViewById<LinearLayout>(R.id.stepsContainer)
        val tv = TextView(this).apply {
            text = msg
            setTextColor(android.graphics.Color.parseColor("#F44336"))
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }
        container.addView(tv)
    }

    private fun showStep(container: LinearLayout, label: String, bitmap: Bitmap) {
        val stepView = layoutInflater.inflate(R.layout.item_processing_step, container, false)
        stepView.findViewById<TextView>(R.id.stepLabel).text = label
        stepView.findViewById<ImageView>(R.id.stepImage).setImageBitmap(bitmap)
        container.addView(stepView)

        findViewById<android.widget.ScrollView>(R.id.scrollView).post {
            findViewById<android.widget.ScrollView>(R.id.scrollView).fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        recognizer.close()
        BitmapHolder.bitmap = null
    }
}