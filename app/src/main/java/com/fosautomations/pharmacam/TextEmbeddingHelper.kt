package com.fosautomations.pharmacam

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import java.io.File

class TextEmbeddingHelper(private val context: Context) : AutoCloseable {

    private var textEmbedder: TextEmbedder? = null
    private val modelPath = File(context.getExternalFilesDir(null), "universal_sentence_encoder.tflite").absolutePath

    init {
        try {
            val modelBuffer = loadModelFile(modelPath)
            val baseOptions = BaseOptions.builder()
                .setModelAssetBuffer(modelBuffer)
                .build()
            val options = TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .setL2Normalize(true)
                .build()
            textEmbedder = TextEmbedder.createFromOptions(context, options)
            Log.d("TextEmbeddingHelper", "TextEmbedder loaded successfully")
        } catch (e: Exception) {
            Log.e("TextEmbeddingHelper", "Failed to load TextEmbedder model: ${e.message}", e)
        }
    }

    private fun loadModelFile(path: String): java.nio.ByteBuffer {
        val file = File(path)
        val randomAccessFile = java.io.RandomAccessFile(file, "r")
        val fileChannel = randomAccessFile.channel
        return fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }

    /**
     * Generates a float vector for the given text.
     */
    fun getEmbedding(text: String): FloatArray? {
        val embedder = textEmbedder ?: return null
        return try {
            val result = embedder.embed(text)
            result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
        } catch (e: Exception) {
            Log.e("TextEmbeddingHelper", "Embedding failed for: $text - ${e.message}")
            null
        }
    }

    override fun close() {
        try {
            textEmbedder?.close()
        } catch (e: Exception) {
            Log.e("TextEmbeddingHelper", "Error closing TextEmbedder: ${e.message}")
        }
    }

    companion object {
        /**
         * Cosine similarity is the dot product of two L2-normalized vectors.
         */
        fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            if (v1.size != v2.size) return 0.0f
            var dotProduct = 0.0f
            for (i in v1.indices) {
                dotProduct += v1[i] * v2[i]
            }
            return dotProduct
        }
    }
}
