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
     * Clustering-grade full face: both eyes, nose base and mouth all
     * detected AND comfortably inside the frame (see [isFullFace]). Only
     * full faces are embedded — partial faces match arbitrarily (measured:
     * a half-face matched the wrong person at 0.54 while scoring 0.17
     * against its own). Near-edge but complete close-ups pass; only
     * genuinely cut-off faces fail.
     */
    val fullFace: Boolean,
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
 * Clustering-grade gate: both eyes, nose base and mouth must be detected
 * AND sit comfortably inside the frame. Box coordinates alone cannot tell
 * a tightly-framed full face from a cut-off one (both touch the boundary —
 * a device run flagged 121/147 faces that way and showed 1 person); only
 * landmarks can. Pure Kotlin, JVM-tested.
 */
fun isFullFace(
    eyeLX: Float?,
    eyeLY: Float?,
    eyeRX: Float?,
    eyeRY: Float?,
    noseX: Float?,
    noseY: Float?,
    mouthX: Float?,
    mouthY: Float?,
    frameW: Int,
    frameH: Int,
    marginFrac: Float = 0.02f,
): Boolean {
    val xs = listOf(eyeLX, eyeRX, noseX, mouthX)
    val ys = listOf(eyeLY, eyeRY, noseY, mouthY)
    if ((xs + ys).any { it == null }) return false
    if (frameW <= 0 || frameH <= 0) return false
    val mx = frameW * marginFrac
    val my = frameH * marginFrac
    val xsn = xs.filterNotNull()
    val ysn = ys.filterNotNull()
    return xsn.min() > mx && xsn.max() < frameW - mx &&
        ysn.min() > my && ysn.max() < frameH - my
}

const val MAX_FACES_PER_FRAME = 6
