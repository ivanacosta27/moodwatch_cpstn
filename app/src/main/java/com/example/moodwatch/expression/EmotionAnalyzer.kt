package com.example.moodwatch.expression

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.util.Log
import android.widget.TextView
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

class EmotionAnalyzer(
    private val classifier: EmotionClassifier,
    private val emotionText: TextView
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    emotionText.post { emotionText.text = "No face detected" }
                    return@addOnSuccessListener
                }

                // Convert to upright Bitmap to crop + classify
                val frameBitmap = imageProxy.toBitmap() ?: run {
                    emotionText.post { emotionText.text = "Frame decode failed" }
                    return@addOnSuccessListener
                }

                val face = faces[0]
                val faceBmp = cropFace(frameBitmap, face, marginRatio = 0.2f)
                if (faceBmp.width <= 0 || faceBmp.height <= 0) {
                    emotionText.post { emotionText.text = "Face crop failed" }
                    return@addOnSuccessListener
                }

                val predicted = classifier.classify(faceBmp)
                emotionText.post { emotionText.text = "Emotion: $predicted" }
            }
            .addOnFailureListener { e ->
                Log.e("EmotionAnalyzer", "Face detection failed", e)
                emotionText.post { emotionText.text = "Detection error" }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Crop with a small margin, safely clamped to bitmap bounds. */
    private fun cropFace(bitmap: Bitmap, face: Face, marginRatio: Float = 0.0f): Bitmap {
        val box = RectF(face.boundingBox)
        // Expand the rect by marginRatio
        val w = box.width()
        val h = box.height()
        val mx = w * marginRatio / 2f
        val my = h * marginRatio / 2f
        box.inset(-mx, -my)

        val x = box.left.coerceAtLeast(0f).toInt()
        val y = box.top.coerceAtLeast(0f).toInt()
        val right = box.right.coerceAtMost(bitmap.width.toFloat()).toInt()
        val bottom = box.bottom.coerceAtMost(bitmap.height.toFloat()).toInt()

        val cw = (right - x).coerceAtLeast(0)
        val ch = (bottom - y).coerceAtLeast(0)
        return if (cw > 0 && ch > 0) Bitmap.createBitmap(bitmap, x, y, cw, ch) else bitmap
    }
}

/** Minimal ImageProxy -> upright Bitmap (NV21 -> JPEG -> Bitmap).
 *  NOTE: simple and readable; may show color artifacts on some devices.
 *  If you notice issues, replace with a proper YUV->RGB converter later. */
fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)

    // NV21 expects interleaved VU bytes
    val vBytes = ByteArray(vSize).also { vBuffer.get(it) }
    val uBytes = ByteArray(uSize).also { uBuffer.get(it) }

    var offset = ySize
    var vPos = 0
    var uPos = 0
    val pixelStride = planes[2].pixelStride
    while (vPos < vSize && uPos < uSize) {
        nv21[offset++] = vBytes[vPos]
        nv21[offset++] = uBytes[uPos]
        vPos += pixelStride
        uPos += pixelStride
    }

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
    val jpeg = out.toByteArray()

    var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null

    // Rotate upright to match preview orientation
    val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    return bmp
}
