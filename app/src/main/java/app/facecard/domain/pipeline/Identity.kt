package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import app.facecard.domain.model.Person

/** Two people the pipeline suspects are one person (human to confirm). */
data class DupHint(val aId: Int, val bId: Int, val sim: Float)

/**
 * Human-oracle merge: combine [drop] into [keep]. Members unite,
 * appearances re-segment from the union, best re-picked. Keeps [keep]'s
 * label so collage numbering stays stable. Pure Kotlin, tested.
 */
fun mergeMembers(keep: Person, drop: Person): Person {
    require(keep.id != drop.id)
    val members = (keep.samples + drop.samples).sortedBy { it.tsMs }
    require(members.isNotEmpty())
    return Person(
        id = keep.id,
        label = keep.label,
        samples = members,
        appearances = AppearanceSegmenter.segment(members),
        best = QualityScorer.best(members),
    )
}

/**
 * Duplicate suspects for the UI: pairs that NEVER share screen time with
 * centroid similarity in [DUP_HINT_MIN, merge bar). Below the band they are
 * clearly distinct; at/above it without overlap the auto-merge already took
 * them. Overlapping pairs stay silent — shared frames are either genuinely
 * two people or fragments the dissolve pass already ruled on; offering a
 * merge there is how distinct co-stars get fused.
 */
fun computeDupHints(people: List<Person>): List<DupHint> {
    if (people.size < 2) return emptyList()
    val valid = people.filter { it.samples.isNotEmpty() }
    val cents = valid.associate { it.id to Clusterer.centroidOf(it.samples) }
    val out = mutableListOf<DupHint>()
    for (i in valid.indices) for (j in i + 1 until valid.size) {
        val a = valid[i]
        val b = valid[j]
        val sim = Clusterer.cosine(cents.getValue(a.id), cents.getValue(b.id))
        if (sim >= DUP_HINT_MIN && sim < Clusterer.COSINE_MERGE_THRESHOLD &&
            !AppearanceSegmenter.overlaps(a.appearances, b.appearances)
        ) {
            out.add(DupHint(a.id, b.id, sim))
        }
    }
    return out.sortedByDescending { it.sim }
}

/** Below this, two people are clearly distinct — never hint. */
const val DUP_HINT_MIN = 0.45f

/** Test/demo helper: minimal sample at [tsMs] with a fixed embedding. */
fun hintSample(tsMs: Long, embedding: FloatArray): FaceSample = FaceSample(
    tsMs = tsMs, embedding = embedding, sharpness = 500.0,
    eulerY = 0f, eulerZ = 0f, eyeOpen = 0.9f, smiling = 0.5f,
    edgeClipped = false, area = 40_000, trackingId = null,
    left = 100, top = 100, right = 300, bottom = 300,
    frameW = 640, frameH = 1136, soloFrame = true,
)
