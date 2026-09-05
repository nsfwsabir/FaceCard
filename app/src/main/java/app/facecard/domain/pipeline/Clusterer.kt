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
         * Cosine-similarity operating point. Raised 0.55 → 0.62 after a
         * device run chained all 145 faces into one mega-cluster: this
         * footage's cross-identity pairs reach ~0.55 (blur/close-up mush),
         * so 0.55 links everything to everything. Same-person runs clear
         * 0.62 comfortably; anything below is handled as noise or split
         * (see minPts). Tune at the knee of a k-NN distance plot and
         * re-verify against Sample 1 (5 / 20).
         */
        const val COSINE_THRESHOLD = 0.62f

        /**
         * DBSCAN minPts. Two reasons this is 5, not 2:
         * 1. Smile counts neighbours EXCLUDING the point itself (verified:
         *    triplets cluster at minPts=2, stay noise at 3), so minPts=5 ⟺
         *    a person needs ≥6 mutually-close faces.
         * 2. Higher minPts is the textbook brake on DBSCAN's single-link
         *    effect: one ambiguous pair (shared frame, blur smear) must not
         *    bridge two people into one mega-cluster. Device run showed all
         *    145 faces chaining into a single person at minPts=2; bridges
         *    that thin never reach density 5, while real cast members
         *    (dozens of faces each) clear it easily.
         * Coherent with the ≥3-frame appearance rule: anything smaller
         * can't form a countable appearance anyway.
         */
        const val DBSCAN_MIN_PTS = 5

        /** Below this many candidate clusters, noise is kept, not pruned. */
        const val MIN_CLUSTERS_TO_PRUNE = 3
    }
}
