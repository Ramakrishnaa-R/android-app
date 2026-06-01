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
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class InjectionMedicineImageClassifier(context: Context) {
    data class Prediction(val medicineName: String, val confidence: Float)

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputWidth = DEFAULT_INPUT_SIZE
    private var inputHeight = DEFAULT_INPUT_SIZE
    private var inputType = DataType.FLOAT32
    private var outputShape = intArrayOf(1, 1)
    private var outputType = DataType.FLOAT32

    init {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
            labels = context.assets.open(LABELS_NAME).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
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
            Log.d(TAG, "Loaded $MODEL_NAME with ${labels.size} injection labels")
        } catch (e: Exception) {
            Log.w(TAG, "Injection model not found yet. Add $MODEL_NAME and $LABELS_NAME to assets when converted.")
        }
    }

    fun classify(bitmap: Bitmap): Prediction? {
        val tInterpreter = interpreter ?: return null
        if (labels.isEmpty()) return null

        return try {
            val processor = ImageProcessor.Builder()
                .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                .apply {
                    if (inputType == DataType.FLOAT32) add(NormalizeOp(0f, 255f))
                }
                .build()

            var image = TensorImage(inputType)
            image.load(bitmap)
            image = processor.process(image)

            val output = TensorBuffer.createFixedSize(outputShape, outputType)
            val outputBuffer = output.buffer
            outputBuffer.rewind()
            tInterpreter.run(image.buffer, outputBuffer)

            val scores = output.floatArray
            if (scores.isEmpty()) return null

            val usableCount = minOf(scores.size, labels.size)
            if (usableCount == 0) return null

            val bestIndex = (0 until usableCount).maxByOrNull { scores[it] } ?: return null
            val confidence = scores[bestIndex]
            val medicineName = labels[bestIndex]

            Prediction(medicineName, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Injection medicine classification failed", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "InjectionMedicineClassifier"
        private const val MODEL_NAME = "injection_medicine_classifier.tflite"
        private const val LABELS_NAME = "injection_class_names.txt"
        private const val DEFAULT_INPUT_SIZE = 224
    }
}
