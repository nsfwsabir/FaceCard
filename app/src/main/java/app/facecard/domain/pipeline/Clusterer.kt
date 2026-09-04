package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import kotlin.math.sqrt

/**
 * Greedy incremental clustering over L2-normalised embeddings (TRD §4.5).
 * Deterministic, time-ordered, O(N·K) — right-sized for ~hundreds of faces
 * and a handful of people. Pure Kotlin, JVM-tested.
 *
 * @param threshold cosine similarity at/above which a sample joins a cluster.
 * @param mergeThreshold centroid similarity triggering a post-merge
 *   (repairs frontal↔profile splits).
 * @param minFaces clusters smaller than this are dropped as noise — unless
 *   the video has fewer than 3 clusters total, in which case everything is
 *   kept (never delete a real solo appearance).
 */
class Clusterer(
    val threshold: Float = COSINE_THRESHOLD,
    private val mergeThreshold: Float = COSINE_MERGE_THRESHOLD,
    private val minFaces: Int = MIN_FACES_PER_CLUSTER,
) {

    fun cluster(samples: List<FaceSample>): List<List<FaceSample>> {
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

        // Post-merge pass for splits (e.g. profile vs frontal of same person).
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    if (cosine(centroids[i], centroids[j]) >= mergeThreshold) {
                        clusters[i].addAll(clusters[j])
                        centroids[i] = meanNormalized(clusters[i])
                        clusters.removeAt(j)
                        centroids.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }

        // Noise prune — guarded so small casts are never hollowed out.
        val pruned = if (clusters.size >= MIN_CLUSTERS_TO_PRUNE) {
            clusters.filter { it.size >= minFaces }
        } else {
            clusters
        }
        return pruned.sortedBy { members -> members.minOf { it.tsMs } }
    }

    companion object {
        /** Tuned on Sample 1 (expect 5 clusters); see TRD §5 + README. */
        const val COSINE_THRESHOLD = 0.55f
        const val COSINE_MERGE_THRESHOLD = 0.60f
        const val MIN_FACES_PER_CLUSTER = 3
        const val MIN_CLUSTERS_TO_PRUNE = 3

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) dot += a[i] * b[i]
            return dot
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
