package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import smile.clustering.DBSCAN
import smile.clustering.PartitionClustering
import kotlin.math.sqrt

/**
 * Face clustering (TRD §4.5), in three standard stages:
 *
 * 1. **Smile DBSCAN** (`com.github.haifengl:smile-core:2.6.0`, LGPL-3.0,
 *    pure JVM, offline) over cosine distance → high-precision fragments.
 *    The bar sits deliberately TIGHT: device logcat proved this footage's
 *    cross-identity pairs reach ~0.55–0.67 while same-person drift spans
 *    ~0.5–0.9, so no single threshold separates them — splitting is
 *    recoverable downstream, fusing is not.
 * 2. **Constrained agglomerative merge**: fragment pairs with centroid
 *    similarity ≥ merge bar AND disjoint screen time reunite (textbook
 *    merging with cannot-link constraints; the brief's shared frames hold
 *    distinct people, so co-occurring fragments never merge).
 * 3. **Never-alone dissolve**: a small fragment with zero solo screen time
 *    is shared-frame debris — each sample is reassigned to the nearest
 *    established cluster (nearest-centroid classification) or dropped.
 *
 * Metric note: embeddings are L2-unit-norm, so cosine distance EQUALS
 * Euclidean distance up to scale: d² = 2(1−cos). We feed Smile's KD-tree
 * `fit(double[][], minPts, radius)` overload directly with
 * radius = sqrt(2·(1−threshold)) — no custom distance code at all.
 */
class Clusterer(
    val threshold: Float = COSINE_THRESHOLD,
    private val minPts: Int = DBSCAN_MIN_PTS,
    private val mergeThreshold: Float = MERGE_THRESHOLD,
) {

    fun cluster(
        samples: List<FaceSample>,
        logger: ((String) -> Unit)? = null,
    ): List<List<FaceSample>> {
        if (samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.tsMs }
        // NaN hygiene: non-finite embeddings poison neighborhoods — drop
        // them first (a corrupt crop must never merge everything).
        val valid = ordered.filter { s -> s.embedding.all { it.isFinite() } }
        val droppedNaN = ordered.size - valid.size
        if (droppedNaN > 0) {
            logger?.invoke("drop $droppedNaN non-finite embeddings")
        }
        if (valid.isEmpty()) return emptyList()

        // ---- Stage 1: DBSCAN fragments ----
        val data = Array(valid.size) { i ->
            DoubleArray(valid[i].embedding.size) { j -> valid[i].embedding[j].toDouble() }
        }
        val radius = sqrt(2.0 * (1.0 - threshold))
        val model = DBSCAN.fit(data, minPts, radius)
        val labels = model.y
        val clusters = mutableListOf<MutableList<FaceSample>>()
        val noise = mutableListOf<FaceSample>()
        val byLabel = mutableMapOf<Int, MutableList<FaceSample>>()
        for (i in valid.indices) {
            val label = labels[i]
            if (label == PartitionClustering.OUTLIER) {
                noise.add(valid[i])
            } else {
                byLabel.getOrPut(label) { mutableListOf() }.add(valid[i])
            }
        }
        clusters.addAll(byLabel.values)
        logger?.invoke(
            "dbscan k=${model.k} noise=${noise.size} " +
                "sizes=${clusters.map { it.size }}",
        )

        // Noise rule: lone singletons in a big cast are false hits; in a
        // tiny cast every face counts (recall over precision).
        if (clusters.size + noise.size >= MIN_CLUSTERS_TO_PRUNE) {
            if (noise.isNotEmpty()) logger?.invoke("prune ${noise.size} noise faces")
        } else {
            noise.forEach { clusters.add(mutableListOf(it)) }
        }
        if (clusters.isEmpty()) return emptyList()
        val centroids = clusters.map { meanNormalized(it) }.toMutableList()

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
        // A small cluster with ZERO solo samples — every face co-occurs
        // with established people — is shared-frame debris, not a person.
        // Reassign each sample to the nearest established cluster
        // (≥ duplicate-grade bar) or drop it. Clusters with any solo
        // screen time are immune; if nobody is established, nothing
        // dissolves (e.g. a video that is one single shared frame).
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
        return clusters.sortedBy { members -> members.minOf { it.tsMs } }
    }

    companion object {
        /**
         * DBSCAN operating point (similarity). Deliberately tight: device
         * logcat proved cross-identity pairs on this footage reach ~0.55
         * while same-person drift spans ~0.5–0.9, so any looser bar fuses
         * distinct people (a whole 145-face run chained into one person at
         * 0.55). Splits are recoverable via stages 2–3; fusions are not.
         * Tune at the knee of the embed_qc distribution, re-verify Sample 1.
         */
        const val COSINE_THRESHOLD = 0.70f

        /**
         * DBSCAN minPts. Smile counts neighbours EXCLUDING the point itself
         * (verified), so minPts=5 ⟺ a person needs ≥6 mutually-close faces —
         * coherent with multi-second appearances at 5fps. Higher minPts is
         * the textbook brake on single-link chaining.
         */
        const val DBSCAN_MIN_PTS = 5

        /**
         * Centroid bar for stage-2 merges (disjoint screen time required).
         * Sits below the fragment bar so genuinely split drift reunites,
         * while the overlap guard protects shared frames.
         */
        const val MERGE_THRESHOLD = 0.60f

        /**
         * Never-alone clusters up to this size dissolve into established
         * people; bigger ones are kept as-is (too much evidence to overrule).
         */
        const val DISSOLVE_MAX_SIZE = 12

        /**
         * Per-sample bar for dissolve reassignment. Duplicate-grade on
         * purpose: dissolving below this once fed a real always-shared
         * person to the nearest big cluster. Ambiguous leftovers survive
         * as their own person instead.
         */
        const val DISSOLVE_REASSIGN = 0.55f

        /** Below this many candidate clusters, noise is kept, not pruned. */
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
