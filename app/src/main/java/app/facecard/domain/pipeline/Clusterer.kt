package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import smile.clustering.DBSCAN
import smile.clustering.PartitionClustering
import kotlin.math.sqrt

/**
 * Face clustering via Smile DBSCAN over cosine distance (TRD §4.5).
 *
 * Library: `com.github.haifengl:smile-core:2.6.0` (LGPL-3.0, pure JVM,
 * offline — no backend, no native code). DBSCAN is the textbook algorithm
 * for face clustering: density chaining heals gradual drift (medium shot →
 * close-up across frames) when intermediate frames exist, native noise
 * labels isolate false hits, and — unlike centroid-following schemes — big
 * clusters can't drift into absorbing distinct people. Ordering barely
 * matters (only edge points between two clusters may swap).
 *
 * Metric note: embeddings are L2-unit-norm, so cosine distance EQUALS
 * Euclidean distance up to scale: d² = 2(1−cos). We feed Smile's KD-tree
 * `fit(double[][], minPts, radius)` overload directly with
 * radius = sqrt(2·(1−threshold)) — no custom distance code at all.
 */
class Clusterer(
    val threshold: Float = COSINE_THRESHOLD,
    private val minPts: Int = DBSCAN_MIN_PTS,
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

        val data = Array(valid.size) { i ->
            DoubleArray(valid[i].embedding.size) { j -> valid[i].embedding[j].toDouble() }
        }
        val radius = sqrt(2.0 * (1.0 - threshold))
        val model = DBSCAN.fit(data, minPts, radius)
        val labels = model.y

        // Group indices per label; noise points become singleton candidates.
        val groups = mutableMapOf<Int, MutableList<FaceSample>>()
        val noise = mutableListOf<FaceSample>()
        for (i in valid.indices) {
            val label = labels[i]
            if (label == PartitionClustering.OUTLIER) {
                noise.add(valid[i])
            } else {
                groups.getOrPut(label) { mutableListOf() }.add(valid[i])
            }
        }
        logger?.invoke(
            "dbscan k=${model.k} noise=${noise.size} " +
                "sizes=${groups.values.map { it.size }}",
        )

        // Noise rule: lone singletons in a big cast are false hits; in a
        // tiny cast every face counts (recall over precision).
        val candidates = groups.values.toMutableList()
        if (candidates.size + noise.size >= MIN_CLUSTERS_TO_PRUNE) {
            if (noise.isNotEmpty()) logger?.invoke("prune ${noise.size} noise faces")
        } else {
            noise.forEach { candidates.add(mutableListOf(it)) }
        }
        return candidates.sortedBy { members -> members.minOf { it.tsMs } }
    }

    companion object {
        /**
         * Cosine-similarity operating point (radius = 1 − threshold in
         * cosine distance ≈ 0.45). Tune at the knee of a k-NN distance plot
         * (standard DBSCAN procedure): lower merges lookalikes, higher
         * splits one person in two. Re-tune only against Sample 1 (5 / 20).
         */
        const val COSINE_THRESHOLD = 0.55f

        /**
         * DBSCAN minPts. Smile counts neighbours EXCLUDING the point itself
         * (verified: triplets cluster at minPts=2, stay noise at 3), so
         * minPts=2 ⟺ a person needs ≥3 mutually-close faces — coherent with
         * the ≥3-frame appearance rule. Anything smaller can't form a
         * countable appearance anyway.
         */
        const val DBSCAN_MIN_PTS = 2

        /** Below this many candidate clusters, noise is kept, not pruned. */
        const val MIN_CLUSTERS_TO_PRUNE = 3
    }
}
