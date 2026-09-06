package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import kotlin.math.abs
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
 *    DISJOINT screen time AND the same screen region reunite (the brief's
 *    shared frames hold distinct people: cannot-link; and two steady
 *    clusters in different regions are different people even when their
 *    appearances never overlap).
 * 3. Never-alone dissolve: a small fragment with zero solo screen time is
 *    usually shared-frame debris — reassigned sample-wise or dropped.
 *    Exception: a temporally coherent fragment sharing frames with
 *    established people at a clearly separated screen position is a
 *    genuinely co-occurring person (split-screen) and is kept.
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
                    if (separatedRegions(clusters[i], clusters[j])) {
                        logger?.invoke(
                            "block merge size=${clusters[i].size}+" +
                                "${clusters[j].size} " +
                                "sim=${"%.3f".format(sim)} " +
                                "(different screen regions)",
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
                val establishedSamples = established.flatMap { clusters[it] }
                val dissolved = clusters.indices.filter { k ->
                    k !in established && clusters[k].size <= DISSOLVE_MAX_SIZE
                }
                for (k in dissolved.sortedDescending()) {
                    if (isSpatiallyDistinctPerson(
                            clusters[k], establishedSamples, logger,
                        )
                    ) {
                        logger?.invoke(
                            "dissolve-keep size=${clusters[k].size} " +
                                "(co-occurring person, separated screen position)",
                        )
                        continue
                    }
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

    /**
     * Merge guard: the same person holds their screen position across
     * segments, so two positionally steady clusters living in clearly
     * different regions are different people — even with disjoint screen
     * time and a passing centroid sim (cross-person pairs reach ~0.55 on
     * real footage). Returns false (abstain → merge allowed) whenever
     * either side carries too little geometry to judge: fewer than two
     * valid boxes, or a wandering position (high MAD, e.g. a moving
     * subject legitimately changing seats across cuts).
     */
    private fun separatedRegions(
        a: List<FaceSample>,
        b: List<FaceSample>,
    ): Boolean {
        val ra = regionOf(a) ?: return false
        val rb = regionOf(b) ?: return false
        return abs(ra.first - rb.first) >= SPATIAL_MIN_DX
    }

    private fun regionOf(members: List<FaceSample>): Pair<Float, Float>? {
        val cxs = members.mapNotNull { normalizedCenter(it)?.first }
        if (cxs.size < 2) return null
        val sorted = cxs.sorted()
        val median = sorted[sorted.size / 2]
        val mad = sorted.map { abs(it - median) }.sorted()
            .let { it[it.size / 2] }
        if (mad > REGION_MAX_MAD) return null
        return Pair(median, mad)
    }

    /**
     * Split-screen guard for stage 3. A never-alone fragment that forms a
     * real appearance AND shares frames with established people at a
     * clearly separated screen position is a genuinely co-occurring
     * person — not duplicate debris. Same-frame box geometry is the
     * deciding signal: drift duplicates never share a frame with their
     * source at a distance, while a split-screen guest does so in every
     * shared frame. Degenerate/missing geometry abstains (false), so old
     * behavior — and old tests — hold wherever boxes carry no signal.
     */
    private fun isSpatiallyDistinctPerson(
        candidate: List<FaceSample>,
        establishedSamples: List<FaceSample>,
        logger: ((String) -> Unit)? = null,
    ): Boolean {
        if (AppearanceSegmenter.segment(candidate)
                .none { it.frames >= AppearanceSegmenter.MIN_SEGMENT_LEN }
        ) return false
        val byTs = establishedSamples.groupBy { it.tsMs }
        var paired = 0
        var separated = 0
        for (s in candidate) {
            val others = byTs[s.tsMs].orEmpty()
            if (others.isEmpty()) continue
            val sc = normalizedCenter(s) ?: continue
            var farFromAll = true
            var comparable = false
            for (o in others) {
                val oc = normalizedCenter(o) ?: continue
                val iou = boxIou(s, o) ?: continue
                comparable = true
                val dx = abs(sc.first - oc.first)
                if (dx < SPATIAL_MIN_DX || iou > SPATIAL_MAX_IOU) {
                    farFromAll = false
                    break
                }
            }
            if (!comparable) continue
            paired++
            if (farFromAll) separated++
        }
        logger?.invoke(
            "dissolve-spatial size=${candidate.size} " +
                "paired=$paired separated=$separated",
        )
        return paired >= SPATIAL_MIN_PAIRED && separated == paired
    }

    private fun normalizedCenter(s: FaceSample): Pair<Float, Float>? {
        if (s.frameW <= 0 || s.frameH <= 0) return null
        val w = s.right - s.left
        val h = s.bottom - s.top
        if (w <= 0 || h <= 0) return null
        return Pair(
            ((s.left + s.right) / 2f) / s.frameW,
            ((s.top + s.bottom) / 2f) / s.frameH,
        )
    }

    private fun boxIou(a: FaceSample, b: FaceSample): Float? {
        if (a.frameW <= 0 || a.frameH <= 0 ||
            b.frameW <= 0 || b.frameH <= 0
        ) return null
        val ax0 = a.left / a.frameW.toFloat()
        val ay0 = a.top / a.frameH.toFloat()
        val ax1 = a.right / a.frameW.toFloat()
        val ay1 = a.bottom / a.frameH.toFloat()
        val bx0 = b.left / b.frameW.toFloat()
        val by0 = b.top / b.frameH.toFloat()
        val bx1 = b.right / b.frameW.toFloat()
        val by1 = b.bottom / b.frameH.toFloat()
        val iw = (minOf(ax1, bx1) - maxOf(ax0, bx0)).coerceAtLeast(0f)
        val ih = (minOf(ay1, by1) - maxOf(ay0, by0)).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val union = (ax1 - ax0) * (ay1 - ay0) +
            (bx1 - bx0) * (by1 - by0) - inter
        if (union <= 0f) return null
        return inter / union
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

        /**
         * Same-frame geometry bars for the split-screen keep-rule: the
         * candidate must sit this far (fraction of frame width) from every
         * established face sharing its frames, with negligible box overlap.
         * Opposite halves of a composite sit ~0.5 apart; 0.20 keeps a wide
         * margin while staying far from same-region duplicates (~0).
         */
        const val SPATIAL_MIN_DX = 0.20f
        const val SPATIAL_MAX_IOU = 0.05f

        /** Minimum same-frame pairs required before the keep-rule may fire. */
        const val SPATIAL_MIN_PAIRED = 3

        /**
         * Maximum positional spread (median absolute deviation of
         * normalized face-center x) for a cluster to count as holding its
         * screen region. Wider than this, the subject moves around and
         * the merge region guard abstains.
         */
        const val REGION_MAX_MAD = 0.08f

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
