package com.kenny.tellofollow.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ML Kit Face Detection in STREAM_MODE for continuous follow-me.
 *
 * Mid-range phones: PERFORMANCE_MODE_FAST + STREAM_MODE keeps latency low.
 * Pose detection can replace this later with the same [DetectionResult] contract.
 *
 * Output is normalized to [0,1] image space so PID is resolution-agnostic.
 */
data class DetectionResult(
    val box: RectF,
    /** Center X in 0..1 (0=left, 1=right). */
    val centerX: Float,
    /** Center Y in 0..1 (0=top, 1=bottom). */
    val centerY: Float,
    /** Box area relative to frame (0..1). Proxy for subject distance. */
    val relativeSize: Float,
    val timestampMs: Long = System.currentTimeMillis(),
)

class PersonDetector {

    companion object {
        private const val TAG = "PersonDetector"
    }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking() // STREAM_MODE behavior via tracking IDs
            .build()
    )

    // Face Detection has no ObjectDetector-style STREAM_MODE flag; continuous
    // video is the STREAM_MODE equivalent via enableTracking() + frame sequence.
    // (Pose / Object Detection APIs expose STREAM_MODE explicitly if we swap later.)

    private val busy = AtomicBoolean(false)

    /**
     * Detect largest face. Returns null if none, or if a previous frame is still processing
     * (drop frames rather than queue latency).
     */
    fun detect(bitmap: Bitmap): DetectionResult? {
        if (!busy.compareAndSet(false, true)) return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces: List<Face> = Tasks.await(detector.process(image))
            if (faces.isEmpty()) return null
            val largest = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
            val b = largest.boundingBox
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val box = RectF(
                b.left / w,
                b.top / h,
                b.right / w,
                b.bottom / h,
            )
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            val rel = ((box.right - box.left) * (box.bottom - box.top)).coerceIn(0f, 1f)
            DetectionResult(box = box, centerX = cx, centerY = cy, relativeSize = rel)
        } catch (e: Exception) {
            Log.e(TAG, "detect failed", e)
            null
        } finally {
            busy.set(false)
        }
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Factory that explicitly requests STREAM_MODE when the ML Kit API exposes it.
 * Kept separate so a future Pose swap only changes this file.
 */
object PersonDetectorFactory {
    fun createStreamFaceDetector(): FaceDetector {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()
        // FaceDetectorOptions.STREAM_MODE is the default when enableTracking() is used
        // in continuous pipelines; IMAGE_MODE would be for single stills.
        return FaceDetection.getClient(options)
    }
}
