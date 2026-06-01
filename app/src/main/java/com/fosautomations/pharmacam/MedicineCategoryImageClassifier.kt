package com.fosautomations.pharmacam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlin.math.exp

class MedicineCategoryImageClassifier(context: Context) {

    data class Prediction(
        val category: ProductCategory,
        val confidence: Float,
        val isConfident: Boolean,
        val topK: List<Pair<ProductCategory, Float>>
    )

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputWidth = DEFAULT_INPUT_SIZE
    private var inputHeight = DEFAULT_INPUT_SIZE
    private var inputType = DataType.FLOAT32
    private var outputShape = intArrayOf(1, 1)
    private var outputType = DataType.FLOAT32

    init {
        val modelBuffer = MODEL_CANDIDATES.firstNotNullOfOrNull { name ->
            try {
                FileUtil.loadMappedFile(context, name)
            } catch (_: Exception) {
                null
            }
        }
        if (modelBuffer != null) {
            try {
                interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(4) })
                labels = LABEL_CANDIDATES.firstNotNullOfOrNull { name ->
                    try {
                        context.assets.open(name).bufferedReader().useLines { lines ->
                            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                        }
                    } catch (_: Exception) {
                        null
                    }
                }.orEmpty()
                interpreter?.getInputTensor(0)?.let { tensor ->
                    inputType = tensor.dataType()
                    val shape = tensor.shape()
                    if (shape.size >= 4) {
                        inputHeight = shape[1]
                        inputWidth = shape[2]
                    }
                }
                interpreter?.getOutputTensor(0)?.let { tensor ->
                    outputShape = tensor.shape()
                    outputType = tensor.dataType()
                }
                Log.d(TAG, "Loaded category model (${inputWidth}x$inputHeight) labels=$labels")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize category classifier", e)
                interpreter = null
            }
        } else {
            Log.w(TAG, "Category model not found. Add one of $MODEL_CANDIDATES to assets.")
        }
    }

    fun classify(bitmap: Bitmap): Prediction? {
        val tInterpreter = interpreter ?: return null
        if (labels.isEmpty()) return null

        return try {
            val enhanced = preprocessForClassification(bitmap)
            val processor = ImageProcessor.Builder()
                .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                .apply {
                    if (inputType == DataType.FLOAT32) add(NormalizeOp(0f, 255f))
                }
                .build()

            var image = TensorImage(inputType)
            image.load(enhanced)
            if (enhanced !== bitmap) enhanced.recycle()
            image = processor.process(image)

            val output = TensorBuffer.createFixedSize(outputShape, outputType)
            val outputBuffer = output.buffer
            outputBuffer.rewind()
            tInterpreter.run(image.buffer, outputBuffer)

            val probabilities = toProbabilities(output.floatArray)
            val usableCount = minOf(probabilities.size, labels.size)
            if (usableCount == 0) return null

            val ranked = (0 until usableCount)
                .map { index ->
                    val label = labels[index]
                    val category = ProductCategory.fromLabel(label) ?: ProductCategory.OTHER
                    category to probabilities[index]
                }
                .sortedByDescending { it.second }

            val topK = ranked.take(TOP_K)
            val (bestCategory, bestScore) = topK.first()
            val isConfident = bestScore >= MIN_CONFIDENCE && bestCategory != ProductCategory.OTHER

            Prediction(
                category = bestCategory,
                confidence = bestScore,
                isConfident = isConfident,
                topK = topK
            )
        } catch (e: Exception) {
            Log.e(TAG, "Category classification failed", e)
            null
        }
    }

    /** Mild contrast boost — helps OCR-style labels on curved packs. */
    private fun preprocessForClassification(source: Bitmap): Bitmap {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(source.width, source.height, config)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, -8f,
                0f, 1.12f, 0f, 0f, -8f,
                0f, 0f, 1.12f, 0f, -8f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun toProbabilities(scores: FloatArray): FloatArray {
        if (scores.isEmpty()) return scores
        val sum = scores.sum()
        if (sum in 0.99f..1.01f && scores.all { it >= 0f && it <= 1f }) return scores

        val max = scores.max()
        val expValues = FloatArray(scores.size) { i -> exp((scores[i] - max).toDouble()).toFloat() }
        val expSum = expValues.sum().coerceAtLeast(1e-6f)
        return FloatArray(scores.size) { i -> expValues[i] / expSum }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "MedicineCategoryClassifier"
        private const val DEFAULT_INPUT_SIZE = 224
        /** Used for [Prediction.isConfident] logging; resolver still uses top-1 as image-first. */
        private const val MIN_CONFIDENCE = 0.45f
        private const val TOP_K = 3

        private val MODEL_CANDIDATES = listOf(
            "medicine_category.tflite",
            "medicine_category (1).tflite"
        )
        private val LABEL_CANDIDATES = listOf(
            "medicine_category_labels.txt",
            "medicine_category_labels (1).txt"
        )
    }
}
