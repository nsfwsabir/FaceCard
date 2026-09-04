package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Picks each person's representative shot (TRD §4.7, PRD US-4).
 * Favours frontal, crisp, eyes-open, pleasant, full-face shots;
 * hard vetoes crush closed-eyes / clipped / profile candidates so a
 * better sample from the same cluster always wins. Pure Kotlin, tested.
 */
object QualityScorer {

    data class Breakdown(
        val frontality: Float,
        val sharpness: Float,
        val eyes: Float,
        val smile: Float,
        val size: Float,
        val veto: Float,
        val total: Float,
    )

    fun score(s: FaceSample): Breakdown {
        // 0° → 1, ≥30° combined tilt → 0 (Z counts less than Y).
        val frontality = 1f - min(1f, (abs(s.eulerY) + 0.6f * abs(s.eulerZ)) / 30f)
        // Log-scale: blur gate (60) → 0, very crisp (600) → 1.
        val sharpness = ((ln(s.sharpness + 1.0) - ln(61.0)) /
            (ln(601.0) - ln(61.0))).toFloat().coerceIn(0f, 1f)
        // Face size relative to frame: 8%+ of frame area → 1.
        val size = if (s.frameW > 0 && s.frameH > 0) {
            (s.area.toFloat() / (0.08f * s.frameW * s.frameH)).coerceIn(0f, 1f)
        } else {
            0f
        }
        var veto = 1f
        if (s.eyeOpen < 0.25f) veto *= 0.2f
        if (s.edgeClipped) veto *= 0.3f
        if (abs(s.eulerY) > 35f) veto *= 0.5f
        // Tiny far-away faces upscale into mushy tiles (and are often
        // partial/false hits): below 2% of frame area, halve the score.
        if (s.frameW > 0 && s.frameH > 0 &&
            s.area < 0.02f * s.frameW * s.frameH
        ) {
            veto *= 0.5f
        }
        val total = (0.30f * frontality + 0.30f * sharpness + 0.20f * s.eyeOpen +
            0.15f * s.smiling + 0.05f * size) * veto
        return Breakdown(frontality, sharpness, s.eyeOpen, s.smiling, size, veto, total)
    }

    /**
     * Argmax with sharpness tie-break (deterministic). Strongly prefers
     * solo-frame candidates: a shared frame's generous crop drags the other
     * person into the tile. Falls back to group candidates only when the
     * person never appears alone.
     */
    fun best(samples: List<FaceSample>): FaceSample {
        require(samples.isNotEmpty())
        val pool = samples.filter { it.soloFrame }.ifEmpty { samples }
        return pool.maxWith(
            compareBy<FaceSample> { score(it).total }.thenBy { it.sharpness },
        )
    }
}
