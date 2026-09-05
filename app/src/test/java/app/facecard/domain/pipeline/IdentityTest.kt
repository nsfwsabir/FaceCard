package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * DBSCAN semantics (Smile, eps ≡ sim 0.62, minPts 5): density chaining,
 * native noise, no time guard — identities separate by embedding distance.
 * Groups need ≥6 mutually-close faces (minPts counts neighbours excluding
 * self), coherent with the ≥3-frame appearance rule.
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

    /** [n] identical-embedding samples spaced 200ms from [ts0]. */
    private fun rep(ts0: Long, n: Int, emb: FloatArray): List<FaceSample> =
        List(n) { i -> sample(ts0 + i * 200L, emb) }

    @Test
    fun `two densities form two clusters`() {
        val samples = mutableListOf<FaceSample>()
        repeat(6) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.02f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.02f * i, tiltAxis = 0)))
        }
        val clusters = Clusterer().cluster(samples)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.size == 6 })
    }

    @Test
    fun `gradual drift chains into one person`() {
        // A↔B cosine distance is 0.50 (beyond eps ≡ 0.38): no direct link.
        // But M sits within eps of both, so density chaining unites all 18.
        // This is the medium→close-up healing path for real footage.
        val a = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val m = rep(5000L, 6, floatArrayOf(0.75f, 0.6614f, 0f))
        val b = rep(10000L, 6, floatArrayOf(0.5f, 0.866f, 0f))
        val clusters = Clusterer().cluster(a + m + b)
        assertEquals(1, clusters.size)
        assertEquals(18, clusters[0].size)
    }

    @Test
    fun `gap without bridge stays split`() {
        val a = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val b = rep(5000L, 6, floatArrayOf(0.5f, 0.866f, 0f))
        val clusters = Clusterer().cluster(a + b)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `overlapping similar faces share one cluster`() {
        // No time guard in DBSCAN: co-occurring faces this similar are one
        // density region. Distinct co-stars separate by DISTANCE (their
        // cross-identity similarity is far lower), not by screen time.
        val xs = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val ys = List(6) { i -> sample(100L + i * 200L, floatArrayOf(0.95f, 0.3122f, 0f)) }
        val clusters = Clusterer().cluster(xs + ys)
        assertEquals(1, clusters.size)
        assertEquals(12, clusters[0].size)
    }

    @Test
    fun `lone noise is dropped in a big cast`() {
        val samples = mutableListOf<FaceSample>()
        repeat(6) { i ->
            samples.add(sample(i * 5000L, vec(0, 0.02f * i)))
            samples.add(sample(i * 5000L + 200L, vec(1, 0.02f * i, tiltAxis = 0)))
            samples.add(sample(i * 5000L + 400L, vec(2, 0.02f * i, tiltAxis = 0)))
        }
        samples.add(sample(60_000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f)))
        val clusters = Clusterer().cluster(samples)
        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.size == 6 })
    }

    @Test
    fun `tiny cast keeps noise as people`() {
        val sextet = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val stray = sample(60_000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f))
        val clusters = Clusterer().cluster(sextet + stray)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `non-finite embeddings are filtered, never poison neighbours`() {
        val sextet = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val nan = sample(5000L, floatArrayOf(Float.NaN, 0f, 0f))
        val clusters = Clusterer().cluster(sextet + nan)
        assertEquals(1, clusters.size)
        assertEquals(6, clusters[0].size)
    }

    @Test
    fun `empty input gives empty output`() {
        assertTrue(Clusterer().cluster(emptyList()).isEmpty())
    }

    @Test
    fun `clusters sorted by first appearance`() {
        val late = rep(30_000L, 6, floatArrayOf(1f, 0f, 0f))
        val early = rep(1000L, 6, floatArrayOf(0f, 1f, 0f))
        val clusters = Clusterer().cluster(late + early)
        assertEquals(2, clusters.size)
        assertEquals(1000L, clusters[0].minOf { it.tsMs })
    }

    @Test
    fun `split drift reunites above the merge bar`() {
        // Fragments at sim 0.65 stay split at the tight DBSCAN bar (0.70)
        // but reunite in stage 2: disjoint screen time, bar 0.60 cleared.
        val a = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val b = rep(5000L, 6, floatArrayOf(0.65f, 0.7599f, 0f))
        val clusters = Clusterer().cluster(a + b)
        assertEquals(1, clusters.size)
        assertEquals(12, clusters[0].size)
    }

    @Test
    fun `co-occurring fragments never merge`() {
        // Same pair as above, but sharing screen time: the cannot-link
        // guard (brief shared frames hold distinct people) refuses.
        val a = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val b = List(6) { i -> sample(100L + i * 200L, floatArrayOf(0.65f, 0.7599f, 0f)) }
        val clusters = Clusterer().cluster(a + b)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `never-alone fragment dissolves into its person`() {
        // Fragment lives entirely inside big's [0,2500] window at sim 0.57:
        // below the merge bar, above the duplicate-grade dissolve bar.
        val big = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val frag = List(6) { i -> sample(300L + i * 200L, floatArrayOf(0.57f, 0.8216f, 0f)) }
        // Spread big across [0,2500] so it actually covers the fragment.
        val bigSpread = listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L)
            .map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val clusters = Clusterer().cluster(bigSpread + frag)
        assertEquals(1, clusters.size)
        assertEquals(12, clusters[0].size)
    }

    @Test
    fun `sub-threshold never-alone fragment is dropped`() {
        // Same setup at sim 0.50: below every bar. It must vanish rather
        // than survive as a bogus ×1 person.
        val bigSpread = listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L)
            .map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val frag = List(6) { i -> sample(300L + i * 200L, floatArrayOf(0.5f, 0.866f, 0f)) }
        val clusters = Clusterer().cluster(bigSpread + frag)
        assertEquals(1, clusters.size)
        assertEquals(6, clusters[0].size)
    }
}
