package app.facecard.domain.pipeline

import app.facecard.domain.model.Appearance
import app.facecard.domain.model.FaceSample

/**
 * Turns one person's time-ordered samples into appearances
 * (PRD counting contract, TRD §4.6).
 *
 * - A gap longer than [gapTolMs] ends the segment (real disappearance/cut).
 * - Shorter gaps are bridged (1–2 missed/blurry frames ≠ a new appearance).
 * - Runs shorter than [minLen] frames are flicker, not appearances —
 *   this is the second whip-pan defence line after the blur gate.
 * - Multi-face frames are inherently per-person: segmentation runs on one
 *   cluster at a time, so A+B sharing a frame counts once for each.
 */
object AppearanceSegmenter {

    fun segment(
        samples: List<FaceSample>,
        gapTolMs: Long = GAP_TOL_MS,
        minLen: Int = MIN_SEGMENT_LEN,
    ): List<Appearance> {
        if (samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.tsMs }
        val out = mutableListOf<Appearance>()
        var runStart = ordered[0].tsMs
        var runPrev = ordered[0].tsMs
        var runLen = 1

        fun close() {
            if (runLen >= minLen) {
                out.add(Appearance(startMs = runStart, endMs = runPrev, frames = runLen))
            }
        }

        for (i in 1 until ordered.size) {
            val ts = ordered[i].tsMs
            if (ts - runPrev <= gapTolMs) {
                runPrev = ts
                runLen++
            } else {
                close()
                runStart = ts
                runPrev = ts
                runLen = 1
            }
        }
        close()
        return out
    }

    /** ≤1s gap = same segment (cuts in the samples sit well above this). */
    const val GAP_TOL_MS = 1000L

    /** ≥3 valid frames ≈ 0.6s @5fps — shorter runs are flicker. */
    const val MIN_SEGMENT_LEN = 3

    /**
     * True when any segment in [a] overlaps any in [b] (shared screen time).
     * Used by the cluster merge guard: two clusters visible SIMULTANEOUSLY
     * cannot be the same person, no matter how similar the centroids.
     */
    fun overlaps(a: List<Appearance>, b: List<Appearance>): Boolean {
        for (x in a) for (y in b) {
            if (x.startMs < y.endMs && y.startMs < x.endMs) return true
        }
        return false
    }
}
