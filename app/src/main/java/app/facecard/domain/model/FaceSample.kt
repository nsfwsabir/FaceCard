package app.facecard.domain.model

/**
 * One validated face observation: kept after the frame blur gate AND the
 * per-face blur gate (Phase 3), with its on-device embedding attached.
 * Everything Phase 5 scoring needs is captured here — bitmaps are gone
 * (recycled); best shots are re-extracted at full res by timestamp.
 */
data class FaceSample(
    val tsMs: Long,
    val embedding: FloatArray,
    val sharpness: Double,
    val eulerY: Float,
    val eulerZ: Float,
    /** min(leftEye, rightEye); 0.5 when ML Kit reports unknown. */
    val eyeOpen: Float,
    /** 0.5 when ML Kit reports unknown. */
    val smiling: Float,
    val edgeClipped: Boolean,
    val area: Int,
    val trackingId: Int?,
)
