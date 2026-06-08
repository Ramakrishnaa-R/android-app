package com.fosautomations.pharmacam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VisionMedicineFinder(private val context: Context) : AutoCloseable {

    private var llmInference: LlmInference? = null
    private val modelPath = File(context.getExternalFilesDir(null), "gemma3n.task").absolutePath

    init {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)     // Need enough space for multimodal input (image + text prompt) + output tokens
                .setMaxNumImages(1)     // we only send 1 image at a time
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            Log.d("VisionMedicineFinder", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("VisionMedicineFinder", "Failed to load model: ${e.message}", e)
        }
    }

    /**
     * Takes a camera bitmap, sends to Gemma 3n vision,
     * returns medicine brand name + strength only.
     */
    suspend fun extractMedicineName(bitmap: Bitmap): String? {
        val inference = llmInference ?: return null

        return withContext(Dispatchers.IO) {
            try {
                // Convert bitmap to MPImage for MediaPipe
                val mpImage = BitmapImageBuilder(bitmap).build()
                
                // Create a clean session for this query to prevent context pollution
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true)
                            .build()
                    )
                    .setTopK(1)
                    .setTemperature(0.1f)
                    .build()
                
                val scanSession = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                scanSession.use { s ->
                    s.addImage(mpImage)
                    
                    val prompt = """
                        Look at this medicine package image.
                        Extract ONLY the brand name and strength/quantity.
                        Examples of correct answers:
                        - DOLO 650
                        - NITROBACT 100
                        - T BACT OINTMENT
                        - RAZO 20
                        - AUGMENTIN 625
                        Reply with ONLY the medicine name and strength.
                        Nothing else. No explanation.
                    """.trimIndent()
                    
                    s.addQueryChunk(prompt)
                    val result = s.generateResponse().trim()
                    
                    Log.d("VisionMedicineFinder", "Gemma 3n vision output: $result")
                    
                    result.uppercase()
                        .take(50)
                        .ifBlank { null }
                }
            } catch (e: Exception) {
                Log.e("VisionMedicineFinder", "Inference failed: ${e.message}", e)
                null
            }
        }
    }

    override fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.e("VisionMedicineFinder", "Error closing llmInference: ${e.message}")
        }
    }
}
