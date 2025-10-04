package com.example.moodwatch.expression

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class EmotionClassifier(context: Context) {

    private val interpreter: Interpreter = Interpreter(loadModelFile(context, "emotion_model.tflite"))

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    // Bitmap → [1][48][48][1] float (0..1)
    private fun bitmapToInputArray(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val size = 48
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val input = Array(1) { Array(size) { Array(size) { FloatArray(1) } } }
        for (y in 0 until size) for (x in 0 until size) {
            val p = scaled.getPixel(x, y)
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toFloat() / 255f
            input[0][y][x][0] = gray
        }
        return input
    }

    fun classify(faceBitmap: Bitmap): String {
        val input = bitmapToInputArray(faceBitmap)
        val output = Array(1) { FloatArray(7) } // 7 emotions
        interpreter.run(input, output)

        // argmax
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in output[0].indices) {
            if (output[0][i] > bestVal) { bestVal = output[0][i]; bestIdx = i }
        }

        val emotions = listOf("Angry", "Disgust", "Fear", "Happy", "Sad", "Surprise", "Neutral")
        return emotions.getOrElse(bestIdx) { "Unknown" }
    }
}
