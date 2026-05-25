package com.fosautomations.pharmacam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class PillDetector(context: Context) {
    private var interpreter: Interpreter? = null
    private val inputSize = 640

    init {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_NAME)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(model, options)
            Log.d(TAG, "Loaded $MODEL_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
        }
    }

    fun detectPills(bitmap: Bitmap): Int {
        val tInterpreter = interpreter ?: run {
            Log.e(TAG, "Interpreter is NULL")
            return 0
        }
        
        return try {
            Log.d(TAG, "Inference STEP 1: Processing Image")
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()

            var tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            Log.d(TAG, "Inference STEP 2: Running Model")
            val outputShape = tInterpreter.getOutputTensor(0).shape()
            Log.d(TAG, "Model Output Shape: ${outputShape.contentToString()}")
            
            val outputSize = outputShape.fold(1) { acc, value -> acc * value }
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())
            tInterpreter.run(tensorImage.buffer, outputBuffer)
            
            Log.d(TAG, "Inference STEP 3: Parsing Results")
            outputBuffer.rewind()
            val output = FloatArray(outputSize)
            outputBuffer.asFloatBuffer().get(output)

            val boxes = parseBoxes(output, outputShape)
            val finalCount = nonMaxSuppression(boxes).size
            Log.d(TAG, "Inference COMPLETE: Found $finalCount pills")
            finalCount
        } catch (e: Exception) {
            Log.e(TAG, "Inference FAILED", e)
            0
        }
    }

    private fun parseBoxes(output: FloatArray, shape: IntArray): List<Box> {
        if (shape.size < 3 || shape[0] != 1) return emptyList()
        val dim1 = shape[1]
        val dim2 = shape[2]

        return if (dim1 <= 16 && dim2 > dim1) {
            parseChannelsFirst(output, channels = dim1, points = dim2)
        } else {
            parseRowsFirst(output, rows = dim1, channels = dim2)
        }
    }

    private fun parseChannelsFirst(output: FloatArray, channels: Int, points: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        for (point in 0 until points) {
            val confidence = bestConfidenceChannelsFirst(output, channels, points, point)
            if (confidence < CONFIDENCE_THRESHOLD) continue
            boxes.add(
                Box(
                    centerX = output[point],
                    centerY = output[points + point],
                    width = output[2 * points + point],
                    height = output[3 * points + point],
                    confidence = confidence
                )
            )
        }
        return boxes
    }

    private fun parseRowsFirst(output: FloatArray, rows: Int, channels: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        for (row in 0 until rows) {
            val offset = row * channels
            if (channels < 5 || offset + 4 >= output.size) continue
            val confidence = if (channels == 5) {
                output[offset + 4]
            } else {
                var best = 0f
                for (channel in 4 until channels) {
                    best = max(best, output[offset + channel])
                }
                best
            }
            if (confidence < CONFIDENCE_THRESHOLD) continue
            boxes.add(
                Box(
                    centerX = output[offset],
                    centerY = output[offset + 1],
                    width = output[offset + 2],
                    height = output[offset + 3],
                    confidence = confidence
                )
            )
        }
        return boxes
    }

    private fun bestConfidenceChannelsFirst(output: FloatArray, channels: Int, points: Int, point: Int): Float {
        if (channels == 5) return output[4 * points + point]
        var best = 0f
        for (channel in 4 until channels) {
            best = max(best, output[channel * points + point])
        }
        return best
    }

    private fun nonMaxSuppression(candidates: List<Box>): List<Box> {
        val selected = mutableListOf<Box>()
        val sorted = candidates
            .filter { it.area >= MIN_BOX_AREA }
            .sortedByDescending { it.confidence }

        for (candidate in sorted) {
            if (selected.none { iou(candidate, it) > IOU_THRESHOLD }) {
                selected.add(candidate)
            }
        }
        return selected
    }

    private fun iou(a: Box, b: Box): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private data class Box(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val confidence: Float
    ) {
        val left: Float get() = centerX - width / 2f
        val top: Float get() = centerY - height / 2f
        val right: Float get() = centerX + width / 2f
        val bottom: Float get() = centerY + height / 2f
        val area: Float get() = max(0f, width) * max(0f, height)
    }

    companion object {
        private const val TAG = "PillDetector"
        private const val MODEL_NAME = "pill_count_yolo26_best.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.15f // Lowered from 0.25f to handle shiny foil reflections
        private const val IOU_THRESHOLD = 0.45f
        private const val MIN_BOX_AREA = 50f // Lowered to catch smaller pills
    }
}
