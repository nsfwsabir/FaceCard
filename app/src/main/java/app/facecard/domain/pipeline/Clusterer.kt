package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import kotlin.math.sqrt

/**
 * Face clustering (TRD §4.5): online competitive assignment over
 * time-ordered samples, plus constrained repair passes.
 *
 * Why competitive instead of threshold linkage (e.g. DBSCAN): on real
 * footage, same-person and cross-person similarities overlap substantially
 * (device logcat: cross pairs reach ~0.55, drift spans ~0.5–0.9). Linkage
 * merges on ANY sufficient match, so one ambiguous pair fuses two people
 * and the error is unrecoverable — a whole 145-face run chained into one
 * person that way. Competitive assignment instead places each face with
 * its BEST-matching identity; errors stay local misassignments instead of
 * cluster fusions. This is the standard sequential/streaming clustering
 * pattern (cf. BIRCH CF-insertion, sequential k-means): order effects are
 * bounded because centroids stabilise within a few samples.
 *
 * Stages:
 * 1. Streaming argmax assignment in time order (join bar = floor).
 * 2. Constrained agglomerative merge: centroid pairs ≥ merge bar with
 *    DISJOINT screen time reunite (the brief's shared frames hold distinct
 *    people: cannot-link).
 * 3. Never-alone dissolve: a small fragment with zero solo screen time is
 *    shared-frame debris — reassigned sample-wise or dropped.
 * 4. Singleton prune in big casts.
 */
class Clusterer(
    val threshold: Float = JOIN_THRESHOLD,
    private val mergeThreshold: Float = MERGE_THRESHOLD,
    private val minFaces: Int = MIN_FACES_PER_CLUSTER,
) {

    fun cluster(
        samples: List<FaceSample>,
        logger: ((String) -> Unit)? = null,
    ): List<List<FaceSample>> {
        if (samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.tsMs }
        val clusters = mutableListOf<MutableList<FaceSample>>()
        val centroids = mutableListOf<FloatArray>()

        // ---- Stage 1: competitive assignment ----
        for (s in ordered) {
            var best = -1
            var bestSim = threshold
            for (i in clusters.indices) {
                val sim = cosine(s.embedding, centroids[i])
                if (sim >= bestSim) {
                    bestSim = sim
                    best = i
                }
            }
            if (best >= 0) {
                clusters[best].add(s)
                centroids[best] = meanNormalized(clusters[best])
            } else {
                clusters.add(mutableListOf(s))
                centroids.add(s.embedding.copyOf())
            }
        }
        logger?.invoke(
            "assigned ${clusters.size} raw clusters " +
                "sizes=${clusters.map { it.size }}",
        )

        // ---- Stage 2: constrained merge ----
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val sim = cosine(centroids[i], centroids[j])
                    if (sim < mergeThreshold) continue
                    if (AppearanceSegmenter.overlaps(
                            AppearanceSegmenter.segment(clusters[i]),
                            AppearanceSegmenter.segment(clusters[j]),
                        )
                    ) {
                        logger?.invoke(
                            "block merge size=${clusters[i].size}+${clusters[j].size} " +
                                "sim=${"%.3f".format(sim)} (co-occurring people)",
                        )
                        continue
                    }
                    logger?.invoke(
                        "merge size=${clusters[i].size}+${clusters[j].size} " +
                            "sim=${"%.3f".format(sim)}",
                    )
                    clusters[i].addAll(clusters[j])
                    centroids[i] = meanNormalized(clusters[i])
                    clusters.removeAt(j)
                    centroids.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }

        // ---- Stage 3: dissolve never-alone fragments ----
        if (clusters.size >= 2) {
            val segs = clusters.map { AppearanceSegmenter.segment(it) }
            fun coveredByOthers(k: Int, ts: Long): Boolean =
                segs.indices.any { o ->
                    o != k && segs[o].any { ts in it.startMs..it.endMs }
                }
            val established = clusters.indices.filter { k ->
                clusters[k].any { s -> !coveredByOthers(k, s.tsMs) }
            }.toSet()
            if (established.isNotEmpty()) {
                val recipientCentroid = established
                    .associateWith { meanNormalized(clusters[it]) }
                    .toMutableMap()
                val dissolved = clusters.indices.filter { k ->
                    k !in established && clusters[k].size <= DISSOLVE_MAX_SIZE
                }
                for (k in dissolved.sortedDescending()) {
                    var rescued = 0
                    for (s in clusters[k].sortedBy { it.tsMs }) {
                        var best: Int? = null
                        var bestSim = DISSOLVE_REASSIGN
                        for (r in established) {
                            val sim = cosine(s.embedding, recipientCentroid.getValue(r))
                            if (sim >= bestSim) {
                                bestSim = sim
                                best = r
                            }
                        }
                        if (best != null) {
                            clusters[best].add(s)
                            recipientCentroid[best] = meanNormalized(clusters[best])
                            rescued++
                        } else {
                            logger?.invoke("dissolve-drop ts=${s.tsMs}")
                        }
                    }
                    logger?.invoke(
                        "dissolve cluster size=${clusters[k].size} rescued=$rescued",
                    )
                    clusters.removeAt(k)
                }
            }
        }

        // ---- Stage 4: singleton prune (big casts only) ----
        val kept = if (clusters.size >= MIN_CLUSTERS_TO_PRUNE) {
            clusters.filter {
                if (it.size >= minFaces) true
                else {
                    logger?.invoke("prune tiny cluster size=${it.size}")
                    false
                }
            }
        } else {
            clusters
        }
        return kept.sortedBy { members -> members.minOf { it.tsMs } }
    }

    companion object {
        /**
         * Competitive join floor. Below this, a face seeds a new person
         * instead of joining anyone. Set low on purpose: precision comes
         * from the argmax (best match wins), not from the bar. Measured on
         * real footage: clean same-person pairs ≥0.565, clean cross ≤0.293.
         */
        const val JOIN_THRESHOLD = 0.50f

        /** Back-compat alias for the UI/docs threshold display. */
        const val COSINE_THRESHOLD = JOIN_THRESHOLD

        /**
         * Centroid bar for stage-2 merges (disjoint screen time required).
         * Heals splits the join pass leaves behind.
         */
        const val MERGE_THRESHOLD = 0.55f

        /**
         * Never-alone clusters up to this size dissolve into established
         * people; bigger ones are kept as-is (too much evidence to overrule).
         */
        const val DISSOLVE_MAX_SIZE = 12

        /**
         * Per-sample bar for dissolve reassignment. Duplicate-grade on
         * purpose: dissolving below this once fed a real always-shared
         * person to the nearest big cluster.
         */
        const val DISSOLVE_REASSIGN = 0.55f

        /** Clusters smaller than this are pruned (big casts only). */
        const val MIN_FACES_PER_CLUSTER = 2

        /** Below this many candidate clusters, nothing is pruned. */
        const val MIN_CLUSTERS_TO_PRUNE = 3

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) dot += a[i] * b[i]
            // Fail SAFE: NaN must never satisfy a >= comparison.
            return if (dot.isNaN()) -1f else dot
        }

        private fun meanNormalized(members: List<FaceSample>): FloatArray {
            val dim = members.first().embedding.size
            val mean = FloatArray(dim)
            for (m in members) {
                val e = m.embedding
                for (i in 0 until minOf(dim, e.size)) mean[i] += e[i]
            }
            var norm = 0f
            for (v in mean) norm += v * v
            norm = sqrt(norm)
            if (norm > 1e-9f) {
                for (i in mean.indices) mean[i] /= norm
            }
            return mean
        }
    }
}
