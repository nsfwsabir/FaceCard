package app.facecard.data.face

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface FaceDetector : Closeable {
    /** Detects faces in an upright detection-size bitmap. Must run off the main thread. */
    suspend fun detect(bitmap: Bitmap): List<DetectedFace>
}

/**
 * Bundled-model ML Kit detector (`com.google.mlkit:face-detection`).
 * Deliberately NOT the Play-Services thin client: the bundled model ships
 * inside the APK, so detection works fully offline on first launch —
 * a hard requirement of the brief (100% on-device, no backend).
 */
class MlKitFaceDetector : FaceDetector {

    private val client = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build(),
    )

    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> =
        suspendCancellableCoroutine { cont ->
            val task = client.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnSuccessListener { faces ->
                val fw = bitmap.width
                val fh = bitmap.height
                cont.resume(
                    faces.mapNotNull { f ->
                        val b = f.boundingBox
                        if (b.isEmpty) return@mapNotNull null
                        DetectedFace(
                            left = b.left.coerceIn(0, fw),
                            top = b.top.coerceIn(0, fh),
                            right = b.right.coerceIn(0, fw),
                            bottom = b.bottom.coerceIn(0, fh),
                            eulerY = f.headEulerAngleY,
                            eulerZ = f.headEulerAngleZ,
                            leftEyeOpen = f.leftEyeOpenProbability,
                            rightEyeOpen = f.rightEyeOpenProbability,
                            smiling = f.smilingProbability,
                            trackingId = f.trackingId,
                            edgeClipped = isEdgeClipped(
                                b.left, b.top, b.right, b.bottom, fw, fh,
                            ),
                        )
                    },
                )
            }
            task.addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    override fun close() {
        runCatching { client.close() }
    }

    companion object {
        /** Ignore tiny background faces (relative to image width). */
        const val MIN_FACE_SIZE = 0.12f
    }
}
