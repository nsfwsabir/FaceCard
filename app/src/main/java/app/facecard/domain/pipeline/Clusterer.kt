package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import kotlin.math.sqrt

/**
 * Greedy incremental clustering over L2-normalised embeddings (TRD §4.5).
 * Deterministic, time-ordered, O(N·K) — right-sized for ~hundreds of faces
 * and a handful of people. Pure Kotlin, JVM-tested.
 *
 * @param threshold cosine similarity at/above which a sample joins a cluster.
 * @param mergeThreshold centroid bar for merging two ESTABLISHED clusters
 *   (both sizeable): deliberately high — big-cluster centroids go generic
 *   and agree spuriously in the 0.50s (device logcat proved it).
 * @param orphanMergeThreshold lower bar for rescues involving a TINY
 *   fragment (≤ [TINY_ORPHAN_MAX] samples): overlap allowed, since a
 *   fragment is debris, not a co-occurring person.
 * @param minFaces clusters smaller than this are pruned as noise — pairs
 *   survive (a brief but real appearance), lone singletons in a big cast
 *   don't. Pruning is skipped entirely when the video has fewer than 3
 *   clusters, so small casts are never hollowed out.
 * @param logger optional merge/prune decision trace (logcat diagnostics).
 */
class Clusterer(
    val threshold: Float = COSINE_THRESHOLD,
    private val mergeThreshold: Float = COSINE_MERGE_THRESHOLD,
    private val orphanMergeThreshold: Float = COSINE_ORPHAN_MERGE,
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
        logger?.invoke(
            "formed ${clusters.size} raw clusters " +
                "sizes=${clusters.map { it.size }}",
        )

        // Post-merge rescue net, two tiers. Established+established pairs
        // (like the brief's shared frames A+B @10.1-11.5s, C+D @20.2-21.6s)
        // need a HIGH bar plus disjoint screen time — big centroids agree
        // spuriously down in the 0.50s. Tiny fragments get the LOW bar with
        // overlap allowed: a 1–2 sample splinter is debris, never a
        // co-occurring person (those leave many samples, not splinters).
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val sim = cosine(centroids[i], centroids[j])
                    val tiny = minOf(clusters[i].size, clusters[j].size) <= TINY_ORPHAN_MAX
                    val bar = if (tiny) orphanMergeThreshold else mergeThreshold
                    if (sim < bar) continue
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

        // Dissolve never-alone fragments: a small cluster (≤ DISSOLVE_MAX)
        // with ZERO solo samples — every one of its faces co-occurs with
        // established people — is shared-frame debris (the ×1 bogus-person
        // failure), not a person. Each sample is reassigned to the nearest
        // established cluster (≥ DISSOLVE_REASSIGN) or dropped. Clusters
        // with any solo screen time are immune; if nobody is established,
        // nothing dissolves (e.g. a video that is one single shared frame).
        if (kept.size >= 2) {
            val segs = kept.map { AppearanceSegmenter.segment(it) }
            fun coveredByOthers(k: Int, ts: Long): Boolean =
                segs.indices.any { o ->
                    o != k && segs[o].any { ts in it.startMs..it.endMs }
                }
            val established = kept.indices.filter { k ->
                kept[k].any { s -> !coveredByOthers(k, s.tsMs) }
            }.toSet()
            if (established.isNotEmpty()) {
                val dissolved = kept.indices.filter { k ->
                    k !in established && kept[k].size <= DISSOLVE_MAX_SIZE
                }
                // Recompute centroids for recipients (they only grow).
                val recipientCentroid = kept.indices
                    .filter { it in established }
                    .associateWith { meanNormalized(kept[it]) }
                    .toMutableMap()
                for (k in dissolved.sortedDescending()) {
                    var rescued = 0
                    for (s in kept[k].sortedBy { it.tsMs }) {
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
                            kept[best].add(s)
                            recipientCentroid[best] = meanNormalized(kept[best])
                            rescued++
                            logger?.invoke(
                                "dissolve ts=${s.tsMs} " +
                                    "sim=${"%.3f".format(bestSim)} " +
                                    "-> size=${kept[best].size}",
                            )
                        } else {
                            logger?.invoke("dissolve-drop ts=${s.tsMs}")
                        }
                    }
                    logger?.invoke(
                        "dissolve cluster size=${kept[k].size} rescued=$rescued",
                    )
                    kept.removeAt(k)
                }
            }
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
         * Established+established merge bar. Device logcat showed big,
         * healthy clusters agreeing at 0.51–0.55 with no screen overlap —
         * below this, merging eats real people. Distinct people typically
         * sit < 0.45; same-person drift clears 0.58 comfortably.
         */
        const val COSINE_MERGE_THRESHOLD = 0.58f
        /** Rescue bar for fragments (≤ [TINY_ORPHAN_MAX] samples). */
        const val COSINE_ORPHAN_MERGE = 0.50f
        /** At or below this size, a cluster counts as a fragment for merging. */
        const val TINY_ORPHAN_MAX = 2
        /**
         * Never-alone clusters up to this size dissolve into established
         * people; bigger ones are kept as-is (too much evidence to overrule).
         */
        const val DISSOLVE_MAX_SIZE = 12
                /**
         * Per-sample bar for dissolve reassignment into an established
         * cluster. Deliberately duplicate-grade (≥ join bar): dissolving at
         * a lower bar fed a real always-shared person to the nearest big
         * cluster on device. Ambiguous leftovers survive as their own
         * person now — the UI's manual merge resolves those with a human.
         */
        const val DISSOLVE_REASSIGN = 0.55f        /**
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

        /** L2-normalised mean embedding: the cluster's identity signature. */
        fun centroidOf(members: List<FaceSample>): FloatArray =
            meanNormalized(members)

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
