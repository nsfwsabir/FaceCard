package app.facecard.data.face

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
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
            // Landmarks feed the full-face gate (isFullFace): box coords
            // alone cannot tell tight close-ups from cut-off faces.
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
                        val l = b.left.coerceIn(0, fw)
                        val t = b.top.coerceIn(0, fh)
                        val r = b.right.coerceIn(0, fw)
                        val bot = b.bottom.coerceIn(0, fh)
                        DetectedFace(
                            left = l,
                            top = t,
                            right = r,
                            bottom = bot,
                            eulerY = f.headEulerAngleY,
                            eulerZ = f.headEulerAngleZ,
                            leftEyeOpen = f.leftEyeOpenProbability,
                            rightEyeOpen = f.rightEyeOpenProbability,
                            smiling = f.smilingProbability,
                            trackingId = f.trackingId,
                            edgeClipped = isEdgeClipped(
                                b.left, b.top, b.right, b.bottom, fw, fh,
                            ),
                            fullFace = isFullFace(
                                eyeLX = f.getLandmark(FaceLandmark.LEFT_EYE)?.position?.x,
                                eyeLY = f.getLandmark(FaceLandmark.LEFT_EYE)?.position?.y,
                                eyeRX = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.x,
                                eyeRY = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.y,
                                noseX = f.getLandmark(FaceLandmark.NOSE_BASE)?.position?.x,
                                noseY = f.getLandmark(FaceLandmark.NOSE_BASE)?.position?.y,
                                mouthX = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.x,
                                mouthY = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.y,
                                frameW = fw,
                                frameH = fh,
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
        /**
         * Relative to image width (detection bitmap). 0.08 catches small
         * faces in split-screen halves and wide group shots that 0.12
         * missed entirely. Extra background hits are contained downstream
         * (singleton prune, tiny-face tile veto).
         */
        const val MIN_FACE_SIZE = 0.08f
    }
}
