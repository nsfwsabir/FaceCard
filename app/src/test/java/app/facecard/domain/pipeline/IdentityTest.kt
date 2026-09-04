package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * DBSCAN semantics (Smile): density chaining, native noise, no time guard —
 * cross-identity pairs separate by distance, not by screen time.
 */
class ClustererTest {

    /**
     * Builds a unit vector leaning mostly along [axis] with slight [tilt]
     * on a DIFFERENT axis (writing tilt onto the axis zeroes it → NaN).
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
    fun `two densities form two clusters`() {
        val samples = mutableListOf<FaceSample>()
        repeat(3) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.02f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.02f * i, tiltAxis = 0)))
        }
        val clusters = Clusterer().cluster(samples)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.size == 3 })
    }

    @Test
    fun `gradual drift chains into one person`() {
        // A↔B cosine distance is 0.50 (beyond eps 0.45): no direct link.
        // But M sits within eps of both, so density chaining unites all 9.
        // This is the medium→close-up healing path for real footage.
        val a = listOf(0L, 200L, 400L).map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val m = listOf(5000L, 5200L, 5400L).map { sample(it, floatArrayOf(0.75f, 0.6614f, 0f)) }
        val b = listOf(10000L, 10200L, 10400L).map { sample(it, floatArrayOf(0.5f, 0.866f, 0f)) }
        val clusters = Clusterer().cluster(a + m + b)
        assertEquals(1, clusters.size)
        assertEquals(9, clusters[0].size)
    }

    @Test
    fun `gap without bridge stays split`() {
        val a = listOf(0L, 200L, 400L).map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val b = listOf(5000L, 5200L, 5400L).map { sample(it, floatArrayOf(0.5f, 0.866f, 0f)) }
        val clusters = Clusterer().cluster(a + b)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `overlapping similar faces share one cluster`() {
        // No time guard in DBSCAN: co-occurring faces this similar are one
        // density region. Distinct co-stars separate by DISTANCE (their
        // cross-identity similarity is far lower), not by screen time.
        val xs = listOf(0L, 200L, 400L).map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val ys = listOf(100L, 300L, 500L).map {
            sample(it, floatArrayOf(0.95f, 0.3122f, 0f))
        }
        val clusters = Clusterer().cluster(xs + ys)
        assertEquals(1, clusters.size)
        assertEquals(6, clusters[0].size)
    }

    @Test
    fun `lone noise is dropped in a big cast`() {
        val samples = mutableListOf<FaceSample>()
        repeat(3) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.02f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.02f * i, tiltAxis = 0)))
            samples.add(sample(i * 5000L + 400L, vec(2, 0.02f * i, tiltAxis = 0)))
        }
        samples.add(sample(60_000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f)))
        val clusters = Clusterer().cluster(samples)
        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.size == 3 })
    }

    @Test
    fun `tiny cast keeps noise as people`() {
        val trio = listOf(0L, 200L, 400L).map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val stray = sample(60_000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f))
        val clusters = Clusterer().cluster(trio + stray)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `non-finite embeddings are filtered, never poison neighbours`() {
        val trio = listOf(0L, 200L, 400L).map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val nan = sample(5000L, floatArrayOf(Float.NaN, 0f, 0f))
        val clusters = Clusterer().cluster(trio + nan)
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].size)
    }

    @Test
    fun `empty input gives empty output`() {
        assertTrue(Clusterer().cluster(emptyList()).isEmpty())
    }

    @Test
    fun `clusters sorted by first appearance`() {
        val late = listOf(30_000L, 30_200L, 30_400L)
            .map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val early = listOf(1000L, 1200L, 1400L)
            .map { sample(it, floatArrayOf(0f, 1f, 0f)) }
        val clusters = Clusterer().cluster(late + early)
        assertEquals(2, clusters.size)
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

    @Test
    fun `overlaps detects shared screen time`() {
        val a = AppearanceSegmenter.segment(listOf(0L, 200L, 400L).map(::sample))
        val b = AppearanceSegmenter.segment(listOf(200L, 400L, 600L).map(::sample))
        val c = AppearanceSegmenter.segment(listOf(5000L, 5200L, 5400L).map(::sample))
        assertTrue(AppearanceSegmenter.overlaps(a, b))
        assertTrue(!AppearanceSegmenter.overlaps(a, c))
        assertTrue(!AppearanceSegmenter.overlaps(a, emptyList()))
    }
}
