package app.facecard.data.export

import app.facecard.domain.model.FaceSample
import kotlin.math.max
import kotlin.math.min

/** Pure-Kotlin crop rect (JVM-testable; converted to Rect at draw time). */
data class CropRect(val l: Int, val t: Int, val w: Int, val h: Int)

/**
 * Generous crop around a face for tiles (TRD §4.8, PRD F-9).
 * Maps the detection-space box onto the full-res frame, expands to
 * 2.4× the face box (min 45% of the short side), shifts up slightly for
 * headroom, clamps into the frame. Falls back to the full frame when the
 * crop would exceed it. NEVER a tight face-box crop.
 */
fun generousCrop(s: FaceSample, fullW: Int, fullH: Int): CropRect {
    val bw = s.right - s.left
    val bh = s.bottom - s.top
    if (s.frameW <= 0 || s.frameH <= 0 || bw <= 0 || bh <= 0 || fullW <= 0 || fullH <= 0) {
        return CropRect(0, 0, fullW.coerceAtLeast(1), fullH.coerceAtLeast(1))
    }
    val sx = fullW.toFloat() / s.frameW
    val sy = fullH.toFloat() / s.frameH
    val cx = ((s.left + s.right) / 2f) * sx
    var cy = ((s.top + s.bottom) / 2f) * sy
    var side = max(bw * sx, bh * sy) * 2.4f
    side = max(side, 0.45f * min(fullW, fullH))
    cy -= side * 0.08f // headroom: faces sit slightly below tile centre
    if (side >= min(fullW, fullH) * 0.98f) {
        return CropRect(0, 0, fullW, fullH)
    }
    val half = side / 2f
    val l = (cx - half).coerceIn(0f, (fullW - side).coerceAtLeast(0f))
    val t = (cy - half).coerceIn(0f, (fullH - side).coerceAtLeast(0f))
    val w = min(side.toInt(), fullW - l.toInt())
    val h = min(side.toInt(), fullH - t.toInt())
    return CropRect(l.toInt(), t.toInt(), w.coerceAtLeast(1), h.coerceAtLeast(1))
}
