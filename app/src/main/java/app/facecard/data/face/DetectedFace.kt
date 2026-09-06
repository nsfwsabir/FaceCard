package app.facecard.data.face

/**
 * One ML Kit face hit, in detection-bitmap pixel coords (upright frame).
 * Plain Int coords — no Android types, so capping/edge logic is JVM-testable.
 * Render-time code converts to RectF as needed.
 */
data class DetectedFace(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val eulerY: Float,
    val eulerZ: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
    val trackingId: Int?,
    val edgeClipped: Boolean,
    /**
     * True only when the (already clamped) box touches the frame boundary:
     * part of the face is physically outside the image, so its embedding
     * is unreliable. Unlike [edgeClipped] (an 8% compositional margin used
     * for scoring), this fires only on real cut-off — close-ups with a few
     * pixels of clearance pass through.
     */
    val cutOff: Boolean,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
}

/**
 * TRD perf rule: cap faces per frame to the largest few.
 * Background crowds beyond this are ignored by design (documented limit).
 */
fun List<DetectedFace>.largest(limit: Int = MAX_FACES_PER_FRAME): List<DetectedFace> =
    sortedByDescending { it.area }.take(limit.coerceAtLeast(1))

/**
 * A face counts as clipped when its box comes within [marginFrac] of the
 * frame edge (relative to the short side). Clipped faces are heavily
 * penalised at best-shot scoring (TRD §4.7) — never tile material if
 * a better candidate exists.
 */
fun isEdgeClipped(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    frameW: Int,
    frameH: Int,
    marginFrac: Float = 0.08f,
): Boolean {
    if (frameW <= 0 || frameH <= 0) return true
    val m = (minOf(frameW, frameH) * marginFrac)
    return left < m || top < m || right > frameW - m || bottom > frameH - m
}

/**
 * True only when the clamped box touches the frame boundary: part of the
 * face is physically outside the image. A fully visible face essentially
 * never aligns pixel-exact with the boundary. Faces only NEAR the edge
 * (close-up framing) correctly return false here while still tripping the
 * wider [isEdgeClipped] compositional margin.
 */
fun isCutOff(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    frameW: Int,
    frameH: Int,
    slop: Int = 1,
): Boolean {
    if (frameW <= 0 || frameH <= 0) return true
    return left <= slop || top <= slop || right >= frameW - slop || bottom >= frameH - slop
}

const val MAX_FACES_PER_FRAME = 6
