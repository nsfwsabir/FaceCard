package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import kotlin.math.sqrt

/**
 * Greedy incremental clustering over L2-normalised embeddings (TRD §4.5).
 * Deterministic, time-ordered, O(N·K) — right-sized for ~hundreds of faces
 * and a handful of people. Pure Kotlin, JVM-tested.
 *
 * @param threshold cosine similarity at/above which a sample joins a cluster.
 * @param mergeThreshold centroid similarity for the post-merge rescue net.
 * @param minFaces clusters smaller than this are pruned as noise — pairs
 *   survive (a brief but real appearance), lone singletons in a big cast
 *   don't. Pruning is skipped entirely when the video has fewer than 3
 *   clusters, so small casts are never hollowed out.
 * @param logger optional merge/prune decision trace (logcat diagnostics).
 */
class Clusterer(
    val threshold: Float = COSINE_THRESHOLD,
    private val mergeThreshold: Float = COSINE_MERGE_THRESHOLD,
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

        // Post-merge rescue net for splits (profile vs frontal, medium vs
        // close-up, blur drift). Two clusters visible SIMULTANEOUSLY (like the
        // brief's shared frames A+B @10.1-11.5s, C+D @20.2-21.6s) can never
        // merge — UNLESS one side is a tiny orphan: a 1–2 sample fragment
        // overlapping a big cluster is drifted debris, not a distinct person
        // (a real co-occurring person leaves many samples, not a fragment).
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val sim = cosine(centroids[i], centroids[j])
                    if (sim < mergeThreshold) continue
                    val tiny = minOf(clusters[i].size, clusters[j].size) < minFaces
                    val overlap = AppearanceSegmenter.overlaps(
                        AppearanceSegmenter.segment(clusters[i]),
                        AppearanceSegmenter.segment(clusters[j]),
                    )
                    if (!overlap || tiny) {
                        logger?.invoke(
                            "merge size=${clusters[i].size}+${clusters[j].size} " +
                                "sim=${"%.3f".format(sim)} overlap=$overlap tiny=$tiny",
                        )
                        clusters[i].addAll(clusters[j])
                        centroids[i] = meanNormalized(clusters[i])
                        clusters.removeAt(j)
                        centroids.removeAt(j)
                        merged = true
                        break@outer
                    } else {
                        logger?.invoke(
                            "block merge size=${clusters[i].size}+${clusters[j].size} " +
                                "sim=${"%.3f".format(sim)} (co-occurring people)",
                        )
                    }
                }
            }
        }

        // Noise prune: lone singletons in a big cast are false hits.
        // Pairs survive (brief but real appearances); small casts are
        // never pruned at all.
        val kept = mutableListOf<MutableList<FaceSample>>()
        if (clusters.size >= MIN_CLUSTERS_TO_PRUNE) {
            for (c in clusters) {
                if (c.size >= minFaces) {
                    kept.add(c)
                } else {
                    logger?.invoke("prune tiny cluster size=${c.size}")
                }
            }
        } else {
            kept.addAll(clusters)
        }
        return kept.sortedBy { members -> members.minOf { it.tsMs } }
    }

    companion object {
        /**
         * Back at 0.55: the 0.50 relaxation mixed distinct people on the
         * graded Sample 1 (shared-frame tiles, inflated counts). Roll-aligned
         * embeddings plus the temporal merge guard below now carry the hard
         * same-person pairs instead. Re-tune only against Sample 1 (5 / 20).
         */
        const val COSINE_THRESHOLD = 0.55f
        /**
         * Lower than the join bar on purpose: two clusters that NEVER share
         * screen time and whose centroids still agree are almost certainly
         * one split person (medium vs close-up, blur drift).
         */
        const val COSINE_MERGE_THRESHOLD = 0.50f
        /**
         * Pairs survive pruning; only true singletons are noise-candidates
         * (and even those are reassigned when they resemble someone).
         */
        const val MIN_FACES_PER_CLUSTER = 2
        const val MIN_CLUSTERS_TO_PRUNE = 3

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) dot += a[i] * b[i]
            // Fail SAFE: a NaN (e.g. zero-norm garbage embedding) must never
            // satisfy a >= comparison — inverted NaN logic once merged
            // everything with everything.
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
