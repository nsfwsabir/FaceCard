package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ClustererTest {

    /**
     * Builds a unit vector leaning mostly along [axis] with slight [tilt].
     * NOTE: [tiltAxis] must differ from [axis] — writing the tilt onto the
     * axis zeroes it (0/0 = NaN), which once silently poisoned these tests.
     */
    private fun vec(axis: Int, tilt: Float = 0f, tiltAxis: Int = 1): FloatArray {
        val v = FloatArray(3)
        v[axis] = 1f
        val ta = if (tiltAxis == axis) (axis + 1) % 3 else tiltAxis
        v[ta] = tilt
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        for (i in v.indices) v[i] /= n
        return v
    }

    private fun sample(ts: Long, emb: FloatArray) = FaceSample(
        tsMs = ts, embedding = emb, sharpness = 500.0,
        eulerY = 0f, eulerZ = 0f, eyeOpen = 0.9f, smiling = 0.5f,
        edgeClipped = false, area = 10_000, trackingId = null,
        left = 100, top = 100, right = 200, bottom = 200,
        frameW = 640, frameH = 640, soloFrame = true,
    )

    @Test
    fun `two people form two clusters`() {
        val samples = mutableListOf<FaceSample>()
        repeat(4) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.05f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.05f * i, tiltAxis = 0)))
        }
        val clusters = Clusterer().cluster(samples)
        assertEquals(2, clusters.size)
        assertEquals(4, clusters[0].size)
        assertEquals(4, clusters[1].size)
    }

    @Test
    fun `singleton dropped when cast is big enough to prune`() {
        val samples = mutableListOf<FaceSample>()
        repeat(3) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.02f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.02f * i, tiltAxis = 0)))
            samples.add(sample(i * 5000L + 400L, vec(2, 0.02f * i, tiltAxis = 0)))
        }
        // One stray false detection, far from everyone (negative octant:
        // cosine with any axis-aligned cast member is ≈ -0.58).
        samples.add(sample(60_000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f)))
        val clusters = Clusterer().cluster(samples)
        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.size == 3 })
    }

    @Test
    fun `singleton kept for tiny casts (no hollowing out solos)`() {
        val samples = mutableListOf(
            sample(0L, vec(0)),
            sample(5000L, vec(0, 0.05f)),
            // Brief second person, single frame.
            sample(10_000L, vec(1)),
        )
        val clusters = Clusterer(minFaces = 3).cluster(samples)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `near-duplicate split is merged back`() {
        // cos(A1, A2) = 0.90 < join 0.95 → split, but ≥ merge 0.90 → merged.
        val a1 = sample(0L, vec(0))
        val a2 = sample(5000L, floatArrayOf(0.90f, 0.4359f, 0f))
        val b = sample(10_000L, vec(1))
        val clusters = Clusterer(threshold = 0.95f, mergeThreshold = 0.90f, minFaces = 1)
            .cluster(listOf(a1, a2, b))
        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first { c -> c.any { it.tsMs == 0L } }.size)
    }

    @Test
    fun `moderately similar non-overlapping pair merges via temporal guard`() {
        // cos ≈ 0.52: below the 0.55 join bar (stays split there) but above
        // the 0.50 merge bar — and with no shared screen time, the guarded
        // merge reunites them. Regression test for medium-vs-close-up splits.
        val a = sample(0L, floatArrayOf(1f, 0f, 0f))
        val b = sample(5000L, floatArrayOf(0.52f, 0.8537f, 0f))
        val clusters = Clusterer(minFaces = 1).cluster(listOf(a, b))
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].size)
    }

    @Test
    fun `overlapping clusters never merge despite similar centroids`() {
        // X and Y look alike (cos 0.95) AND share screen time — the guarded
        // merge must refuse: same person can't be two faces at once. This is
        // what protects the brief's shared frames (A+B, C+D).
        val xs = listOf(0L, 200L, 400L).map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val ys = listOf(100L, 300L, 500L).map {
            sample(it, floatArrayOf(0.95f, 0.3122f, 0f))
        }
        val clusters = Clusterer(threshold = 0.99f, mergeThreshold = 0.90f, minFaces = 1)
            .cluster(xs + ys)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `tiny overlapping orphan is absorbed by the big cluster`() {
        // Big cast member present across [0,800]; a 3-sample fragment at
        // [200,600] (sim 0.60) overlaps it — but a fragment that small is
        // drifted debris, not a distinct co-occurring person. Encodes the
        // shared-frame orphan rescue (×1 bogus-person fix).
        val big = listOf(0L, 200L, 400L, 600L, 800L)
            .map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val orphan = listOf(200L, 400L, 600L)
            .map { sample(it, floatArrayOf(0.6f, 0.8f, 0f)) }
        val clusters = Clusterer(threshold = 0.9f, mergeThreshold = 0.5f, minFaces = 4)
            .cluster(big + orphan)
        assertEquals(1, clusters.size)
        assertEquals(8, clusters[0].size)
    }

    @Test
    fun `pair survives pruning in a big cast`() {
        // minFaces = 2: a brief appearance with two surviving faces is a
        // person, not noise. Only lone singletons are pruned.
        val samples = mutableListOf<FaceSample>()
        repeat(3) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.02f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.02f * i, tiltAxis = 0)))
            samples.add(sample(i * 5000L + 400L, vec(2, 0.02f * i, tiltAxis = 0)))
        }
        val neg = floatArrayOf(-0.5774f, -0.5774f, -0.5774f)
        samples.add(sample(60_000L, neg))
        samples.add(sample(60_200L, neg))
        val clusters = Clusterer().cluster(samples)
        assertEquals(4, clusters.size)
    }

    @Test
    fun `empty input gives empty output`() {
        assertTrue(Clusterer().cluster(emptyList()).isEmpty())
    }

    @Test
    fun `clusters sorted by first appearance`() {
        val late = sample(30_000L, vec(0))
        val early = sample(1000L, vec(1))
        val clusters = Clusterer().cluster(listOf(late, early, sample(31_000L, vec(0, 0.02f))))
        assertEquals(1000L, clusters[0].minOf { it.tsMs })
    }
}

class AppearanceSegmenterTest {

    private fun sample(ts: Long) = FaceSample(
        tsMs = ts, embedding = floatArrayOf(1f, 0f), sharpness = 500.0,
        eulerY = 0f, eulerZ = 0f, eyeOpen = 0.9f, smiling = 0.5f,
        edgeClipped = false, area = 10_000, trackingId = null,
        left = 100, top = 100, right = 200, bottom = 200,
        frameW = 640, frameH = 640, soloFrame = true,
    )

    @Test
    fun `continuous run is one appearance`() {
        val segs = AppearanceSegmenter.segment(
            listOf(0L, 200L, 400L, 600L).map(::sample),
        )
        assertEquals(1, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals(600L, segs[0].endMs)
        assertEquals(4, segs[0].frames)
    }

    @Test
    fun `real cut splits, flicker bridges`() {
        // 0,200,600 bridged (400ms gap); 3000+ is a new appearance.
        val segs = AppearanceSegmenter.segment(
            listOf(0L, 200L, 600L, 3000L, 3200L, 3400L).map(::sample),
        )
        assertEquals(2, segs.size)
        assertEquals(600L, segs[0].endMs)
        assertEquals(3000L, segs[1].startMs)
    }

    @Test
    fun `sub-min-length flicker is dropped (whip-pan defence)`() {
        val segs = AppearanceSegmenter.segment(
            listOf(0L, 200L, 400L, 20_000L).map(::sample),
        )
        assertEquals(1, segs.size)
        assertEquals(400L, segs[0].endMs)
    }

    @Test
    fun `same-timestamp multi-face frames stay in one segment`() {
        // A+B sharing 10.1–11.5s: per-cluster segmentation counts
        // each person's shared frames as their own single appearance.
        val shared = listOf(10_100L, 10_300L, 10_500L, 11_500L).map(::sample)
        val segs = AppearanceSegmenter.segment(shared)
        assertEquals(1, segs.size)
        assertEquals(4, segs[0].frames)
    }

    @Test
    fun `empty input gives empty output`() {
        assertTrue(AppearanceSegmenter.segment(emptyList()).isEmpty())
    }
}
