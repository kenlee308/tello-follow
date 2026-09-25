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
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

enum class DetectionSource {
    BODY,
    FACE,
}

enum class FollowMode {
    BODY,
    FACE,
}

/** Normalized landmark in image space (0..1). */
data class LandmarkPoint(
    val x: Float,
    val y: Float,
    val inFrameLikelihood: Float = 1f,
)

/**
 * Detection for follow-me. Prefer body/pose (torso center + landmark bbox);
 * face remains a fallback when pose misses or [FollowMode.FACE] is selected.
 *
 * Coordinates are normalized to [0,1] so PID is resolution-agnostic.
 *
 * Distance proxy: BODY uses normalized shoulder span (stable when hips/legs
 * are cropped); FACE uses face box area. [distanceMetric] is what PID locks.
 */
data class DetectionResult(
    val box: RectF,
    /** Center X in 0..1 (0=left, 1=right). */
    val centerX: Float,
    /** Center Y in 0..1 (0=top, 1=bottom). */
    val centerY: Float,
    /**
     * Size proxy for distance (0..1). BODY: shoulderWidth/frameWidth;
     * FACE: face box area. Same value as [distanceMetric].
     */
    val relativeSize: Float,
    val timestampMs: Long = System.currentTimeMillis(),
    val source: DetectionSource = DetectionSource.FACE,
    /** Pose landmarks (normalized), empty for face-only hits. */
    val landmarks: List<LandmarkPoint> = emptyList(),
    /** Skeleton edges as index pairs into [landmarks]. */
    val skeletonEdges: List<Pair<Int, Int>> = emptyList(),
    /**
     * Explicit distance metric for PID lock / size error.
     * Defaults to [relativeSize].
     */
    val distanceMetric: Float = relativeSize,
)

/**
 * ML Kit Pose Detection (STREAM_MODE, fast) with Face Detection fallback.
 *
 * Body target: midpoint of shoulders + hips (torso center). If hips are weak,
 * shoulders alone with a slight downward bias. BBox from landmark extents.
 * Distance from shoulder width (not full-body bbox area) so partial upper-body
 * still yields a stable distance signal.
 */
class PersonDetector {

    companion object {
        private const val TAG = "PersonDetector"
        /** Slightly lower so weak hip landmarks do not kill upper-body detections. */
        private const val MIN_LIKELIHOOD = 0.40f

        private val SKELETON_PAIRS: List<Pair<Int, Int>> = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_EAR to PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EAR to PoseLandmark.RIGHT_EYE,
            PoseLandmark.NOSE to PoseLandmark.LEFT_EYE,
            PoseLandmark.NOSE to PoseLandmark.RIGHT_EYE,
        )

        private val DRAW_TYPES: IntArray = intArrayOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE, PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
        )
    }

    private val poseDetector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()
    )

    private val busy = AtomicBoolean(false)

    /**
     * Detect subject. Prefer pose/body when [mode] is BODY; face-only when FACE.
     * Returns null if none, or if a previous frame is still processing.
     */
    fun detect(bitmap: Bitmap, mode: FollowMode = FollowMode.BODY): DetectionResult? {
        if (!busy.compareAndSet(false, true)) return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            when (mode) {
                FollowMode.BODY -> {
                    detectPose(image, bitmap.width, bitmap.height)
                        ?: detectFace(image, bitmap.width, bitmap.height)
                }
                FollowMode.FACE -> detectFace(image, bitmap.width, bitmap.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect failed", e)
            null
        } finally {
            busy.set(false)
        }
    }

    private fun detectPose(image: InputImage, width: Int, height: Int): DetectionResult? {
        val pose: Pose = Tasks.await(poseDetector.process(image))
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        fun lm(type: Int): PoseLandmark? {
            val p = pose.getPoseLandmark(type) ?: return null
            return if (p.inFrameLikelihood >= MIN_LIKELIHOOD) p else null
        }

        // Both shoulders required for a stable torso / distance signal.
        val leftShoulder = lm(PoseLandmark.LEFT_SHOULDER) ?: return null
        val rightShoulder = lm(PoseLandmark.RIGHT_SHOULDER) ?: return null
        // Hips optional — partial upper body (cropped legs) still tracks.
        val leftHip = lm(PoseLandmark.LEFT_HIP)
        val rightHip = lm(PoseLandmark.RIGHT_HIP)

        val shoulderCx = (leftShoulder.position.x + rightShoulder.position.x) / 2f
        val shoulderCy = (leftShoulder.position.y + rightShoulder.position.y) / 2f
        val shoulderWidthPx = kotlin.math.abs(rightShoulder.position.x - leftShoulder.position.x)

        val centerPx: Float
        val centerPy: Float
        if (leftHip != null && rightHip != null) {
            val hipCx = (leftHip.position.x + rightHip.position.x) / 2f
            val hipCy = (leftHip.position.y + rightHip.position.y) / 2f
            centerPx = (shoulderCx + hipCx) / 2f
            centerPy = (shoulderCy + hipCy) / 2f
        } else {
            // Shoulders alone with downward bias toward torso mid.
            centerPx = shoulderCx
            centerPy = shoulderCy + shoulderWidthPx * 0.35f
        }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var any = false
        for (type in DRAW_TYPES) {
            val p = lm(type) ?: continue
            any = true
            val x = p.position.x
            val y = p.position.y
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (!any) {
            minX = centerPx - 40f
            maxX = centerPx + 40f
            minY = centerPy - 60f
            maxY = centerPy + 60f
        }
        val padX = (maxX - minX) * 0.08f + 8f
        val padY = (maxY - minY) * 0.08f + 8f
        minX = (minX - padX).coerceAtLeast(0f)
        minY = (minY - padY).coerceAtLeast(0f)
        maxX = (maxX + padX).coerceAtMost(w)
        maxY = (maxY + padY).coerceAtMost(h)

        val box = RectF(minX / w, minY / h, maxX / w, maxY / h)
        val cx = (centerPx / w).coerceIn(0f, 1f)
        val cy = (centerPy / h).coerceIn(0f, 1f)

        // Distance proxy: normalized shoulder span (stable when hips/legs cropped).
        val shoulderFrac = (shoulderWidthPx / w).coerceIn(0f, 1f)

        val points = ArrayList<LandmarkPoint>(DRAW_TYPES.size)
        val typeToIndex = HashMap<Int, Int>(DRAW_TYPES.size)
        for (type in DRAW_TYPES) {
            val p = pose.getPoseLandmark(type) ?: continue
            // Draw slightly weaker landmarks than the detection gate.
            if (p.inFrameLikelihood < MIN_LIKELIHOOD * 0.6f) continue
            typeToIndex[type] = points.size
            points.add(
                LandmarkPoint(
                    x = (p.position.x / w).coerceIn(0f, 1f),
                    y = (p.position.y / h).coerceIn(0f, 1f),
                    inFrameLikelihood = p.inFrameLikelihood,
                )
            )
        }
        val edges = ArrayList<Pair<Int, Int>>()
        for ((a, b) in SKELETON_PAIRS) {
            val ia = typeToIndex[a] ?: continue
            val ib = typeToIndex[b] ?: continue
            edges.add(ia to ib)
        }

        return DetectionResult(
            box = box,
            centerX = cx,
            centerY = cy,
            relativeSize = shoulderFrac,
            source = DetectionSource.BODY,
            landmarks = points,
            skeletonEdges = edges,
            distanceMetric = shoulderFrac,
        )
    }

    private fun detectFace(image: InputImage, width: Int, height: Int): DetectionResult? {
        val faces: List<Face> = Tasks.await(faceDetector.process(image))
        if (faces.isEmpty()) return null
        val largest = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
        val b = largest.boundingBox
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val box = RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val area = ((box.right - box.left) * (box.bottom - box.top)).coerceIn(0f, 1f)
        return DetectionResult(
            box = box,
            centerX = cx,
            centerY = cy,
            relativeSize = area,
            source = DetectionSource.FACE,
            distanceMetric = area,
        )
    }

    fun close() {
        try {
            poseDetector.close()
        } catch (_: Exception) {
        }
        try {
            faceDetector.close()
        } catch (_: Exception) {
        }
    }
}
