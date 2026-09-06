package app.facecard.domain.pipeline

import app.facecard.domain.model.FaceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Competitive assignment semantics: each face joins its BEST-matching
 * identity (argmax over centroids at/above the join floor), so errors stay
 * local misassignments instead of fusing clusters. Time-ordered, streaming.
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

    /** Cluster (by first timestamp) containing [tsMs], or null. */
    private fun containing(
        clusters: List<List<FaceSample>>,
        tsMs: Long,
    ): List<FaceSample>? = clusters.firstOrNull { c -> c.any { it.tsMs == tsMs } }

    @Test
    fun `two people form two clusters`() {
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
    fun `best match wins over merely passing match`() {
        // s clears A's bar (0.55) but resembles B far more (0.84).
        // Threshold linkage would attach it to whoever it meets first;
        // competition puts it with B. This is the core property.
        val a = rep(0L, 3, floatArrayOf(1f, 0f, 0f))
        val b = rep(10_000L, 3, floatArrayOf(0f, 1f, 0f))
        val s = sample(20_000L, floatArrayOf(0.55f, 0.8352f, 0f))
        val clusters = Clusterer().cluster(a + b + s)
        assertEquals(2, clusters.size)
        assertEquals(clustersOfB(clusters), containing(clusters, 20_000L))
    }

    private fun clustersOfB(clusters: List<List<FaceSample>>): List<FaceSample>? =
        containing(clusters, 10_000L)

    @Test
    fun `below-bar sample seeds a new person`() {
        val a = rep(0L, 3, floatArrayOf(1f, 0f, 0f))
        val stranger = sample(5000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f))
        val clusters = Clusterer().cluster(a + stranger)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `below-merge drift stays split`() {
        // Pair at sim 0.48: below the join floor (no direct link) and below
        // the merge bar — two people, correctly left alone.
        val a = rep(0L, 6, floatArrayOf(1f, 0f, 0f))
        val b = rep(5000L, 6, floatArrayOf(0.48f, 0.8772f, 0f))
        val clusters = Clusterer().cluster(a + b)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.size == 6 })
    }

    @Test
    fun `migration reunites drift through the merge pass`() {
        // s1 can never join directly (0.42 to the early centroid), but once
        // a3 migrates the big centroid toward it, the merge bar (0.55) is
        // met with no screen overlap — reunited. The honest merge path.
        val a1 = sample(0L, floatArrayOf(1f, 0f, 0f))
        val a2 = sample(100L, floatArrayOf(0.984808f, 0.173648f, 0f))
        val s1 = sample(200L, floatArrayOf(0.342020f, 0.939693f, 0f))
        val a3 = sample(300L, floatArrayOf(0.819152f, 0.573576f, 0f))
        val clusters = Clusterer().cluster(listOf(a1, a2, s1, a3))
        assertEquals(1, clusters.size)
        assertEquals(4, clusters[0].size)
    }

    @Test
    fun `co-occurring drift is blocked then kept, never merged`() {
        // The guard's genuine case: big's centroid migrates to sim 0.59 of
        // the fragment (merge bar met) while their segments overlap — so the
        // merge is refused AND dissolve abstains (the fragment owns a solo
        // sample at ts=3000 outside big's span). Two people stay two.
        val big = listOf(0L, 500L, 1000L, 1500L).map {
            sample(it, floatArrayOf(1f, 0f, 0f))
        } + listOf(1900L, 2500L).map {
            sample(it, floatArrayOf(0.906308f, 0.422618f, 0f))
        }
        val frag = listOf(600L, 800L, 1000L, 3000L).map {
            sample(it, floatArrayOf(0.469472f, 0.882948f, 0f))
        }
        val clusters = Clusterer().cluster(big + frag)
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 6 })
        assertTrue(clusters.any { it.size == 4 })
    }

    @Test
    fun `never-alone fragment dissolves into its person`() {
        // Same drift, but the fragment lives entirely inside big's span:
        // no solo sample, so dissolve reassigns each face (sim 0.59 clears
        // the duplicate-grade bar) instead of keeping a bogus person.
        val big = listOf(0L, 500L, 1000L, 1500L).map {
            sample(it, floatArrayOf(1f, 0f, 0f))
        } + listOf(1900L, 2500L).map {
            sample(it, floatArrayOf(0.906308f, 0.422618f, 0f))
        }
        val frag = listOf(600L, 800L, 1000L).map {
            sample(it, floatArrayOf(0.469472f, 0.882948f, 0f))
        }
        val clusters = Clusterer().cluster(big + frag)
        assertEquals(1, clusters.size)
        assertEquals(9, clusters[0].size)
    }

    @Test
    fun `sub-threshold never-alone fragment is dropped`() {
        // Same setup at sim 0.45: below the duplicate-grade dissolve bar,
        // so it vanishes instead of surviving as a bogus ×1 person.
        val bigSpread = listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L)
            .map { sample(it, floatArrayOf(1f, 0f, 0f)) }
        val frag = List(6) { i -> sample(300L + i * 200L, floatArrayOf(0.45f, 0.893f, 0f)) }
        val clusters = Clusterer().cluster(bigSpread + frag)
        assertEquals(1, clusters.size)
        assertEquals(6, clusters[0].size)
    }

    private fun geoSample(
        ts: Long,
        emb: FloatArray,
        left: Int,
        right: Int,
        top: Int = 100,
        bottom: Int = 200,
        frameW: Int = 640,
        frameH: Int = 640,
    ) = FaceSample(
        tsMs = ts, embedding = emb, sharpness = 500.0,
        eulerY = 0f, eulerZ = 0f, eyeOpen = 0.9f, smiling = 0.5f,
        edgeClipped = false, area = 10_000, trackingId = null,
        left = left, top = top, right = right, bottom = bottom,
        frameW = frameW, frameH = frameH, soloFrame = false,
    )

    @Test
    fun `always-shared split-screen guest is kept as its own person`() {
        // Fragment lives entirely inside big's span (never-alone) at sim
        // 0.30, so rescue fails — but it shares every frame from the
        // opposite screen half (dx ~0.66, IoU 0): a real co-occurring
        // person, not debris. Reproduces the Sample 1 size=5 drop.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0.3f, 0.953939f, 0f)
        val big = (0L..2000L step 200L).map { ts ->
            geoSample(ts, embA, left = 40, right = 180)
        }
        val guest = listOf(1000L, 1200L, 1400L, 1600L, 1800L).map { ts ->
            geoSample(ts, embB, left = 460, right = 600)
        }
        val clusters = Clusterer().cluster(big + guest)
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 11 })
        assertTrue(clusters.any { it.size == 5 })
    }

    @Test
    fun `same-region never-alone fragment still dissolves`() {
        // Same shape as the split-screen case, but the fragment sits in
        // the same screen region (identical boxes): drift debris, so the
        // keep-rule must not fire and the old drop path holds.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0.3f, 0.953939f, 0f)
        val big = (0L..2000L step 200L).map { ts ->
            geoSample(ts, embA, left = 40, right = 180)
        }
        val frag = listOf(1000L, 1200L, 1400L, 1600L, 1800L).map { ts ->
            geoSample(ts, embB, left = 40, right = 180)
        }
        val clusters = Clusterer().cluster(big + frag)
        assertEquals(1, clusters.size)
        assertEquals(11, clusters[0].size)
    }

    @Test
    fun `missing geometry abstains from the spatial keep-rule`() {
        // Same shape again, but the fragment carries no usable frame dims:
        // the keep-rule abstains and the sub-threshold drop path holds.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0.3f, 0.953939f, 0f)
        val big = (0L..2000L step 200L).map { ts ->
            geoSample(ts, embA, left = 40, right = 180)
        }
        val frag = listOf(1000L, 1200L, 1400L, 1600L, 1800L).map { ts ->
            geoSample(ts, embB, left = 460, right = 600, frameW = 0, frameH = 0)
        }
        val clusters = Clusterer().cluster(big + frag)
        assertEquals(1, clusters.size)
        assertEquals(11, clusters[0].size)
    }

    @Test
    fun `migration reunites drift in the same screen region`() {
        // Control for the region guard: the migration shape with all
        // boxes identical — the merge must still fire. Only two
        // back-and-forth switches, so the alternation guard stays out.
        val left = Pair(40, 180)
        val samples = listOf(
            geoSample(0L, floatArrayOf(1f, 0f, 0f), left.first, left.second),
            geoSample(100L, floatArrayOf(0.984808f, 0.173648f, 0f), left.first, left.second),
            geoSample(200L, floatArrayOf(0.342020f, 0.939693f, 0f), left.first, left.second),
            geoSample(300L, floatArrayOf(0.819152f, 0.573576f, 0f), left.first, left.second),
        )
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(samples) { logs.add(it) }
        assertEquals(1, clusters.size)
        assertEquals(4, clusters[0].size)
        assertTrue(logs.any { it.startsWith("merge size=") })
    }

    @Test
    fun `merge blocked for steady clusters in different screen regions`() {
        // Same embeddings and timing as the control, but the drift pair
        // lives in the opposite half: disjoint time, passing sim, yet two
        // steady regions — different people, so the merge is refused. The
        // late sample sits seconds out, keeping alternations at two so
        // this stays the region guard's case, not alternation's. The
        // pair's late sample at ts=5000 also owns solo screen time, so
        // dissolve cannot reunite what the guard keeps apart.
        val samples = listOf(
            geoSample(0L, floatArrayOf(1f, 0f, 0f), left = 40, right = 180),
            geoSample(100L, floatArrayOf(0.984808f, 0.173648f, 0f), left = 40, right = 180),
            geoSample(200L, floatArrayOf(0.342020f, 0.939693f, 0f), left = 460, right = 600),
            geoSample(300L, floatArrayOf(0.819152f, 0.573576f, 0f), left = 40, right = 180),
            geoSample(5000L, floatArrayOf(0.342020f, 0.939693f, 0f), left = 460, right = 600),
        )
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(samples) { logs.add(it) }
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 3 })
        assertTrue(clusters.any { it.size == 2 })
        assertTrue(
            logs.any {
                it.startsWith("block merge") && it.contains("different screen regions")
            },
        )
    }

    @Test
    fun `interleaved co-stars never merge`() {
        // Same migration shape as the control (merge sim proven), all
        // boxes identical so the region guard abstains — but the drift
        // pair interleaves with big at 200ms: alternating co-stars, so
        // the merge is refused. This is the alternation guard's doing.
        val samples = listOf(
            geoSample(0L, floatArrayOf(1f, 0f, 0f), left = 40, right = 180),
            geoSample(100L, floatArrayOf(0.984808f, 0.173648f, 0f), left = 40, right = 180),
            geoSample(200L, floatArrayOf(0.342020f, 0.939693f, 0f), left = 40, right = 180),
            geoSample(300L, floatArrayOf(0.819152f, 0.573576f, 0f), left = 40, right = 180),
            geoSample(600L, floatArrayOf(0.342020f, 0.939693f, 0f), left = 40, right = 180),
        )
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(samples) { logs.add(it) }
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 3 })
        assertTrue(clusters.any { it.size == 2 })
        assertTrue(
            logs.any {
                it.startsWith("block merge") && it.contains("interleaved co-stars")
            },
        )
    }

    @Test
    fun `alternating never-alone fragment is kept as co-star`() {
        // Pure sequential interleave (disjoint timestamps, identical
        // boxes): the spatial arm cannot fire and rescue fails at sim
        // 0.30 — but six back-and-forth switches prove a co-star, and its
        // 3-frame run is a countable appearance. Mirrors the Sample 1
        // dialogue edits.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0.3f, 0.953939f, 0f)
        val big = listOf(0L, 200L, 400L, 600L, 800L, 1200L, 1600L, 2000L).map { ts ->
            geoSample(ts, embA, left = 40, right = 180)
        }
        val frag = listOf(1000L, 1400L, 1800L).map { ts ->
            geoSample(ts, embB, left = 40, right = 180)
        }
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(big + frag) { logs.add(it) }
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 8 })
        assertTrue(clusters.any { it.size == 3 })
        assertTrue(logs.any { it.contains("alternating co-star") })
    }

    @Test
    fun `contested face seeds a fragment instead of joining`() {
        // s matches A at 0.53 but B at 0.50: a coin-flip the old floor
        // would silently award to A. It must seed a fragment instead —
        // merge (needs ≥0.55) leaves it alone, it owns solo screen time,
        // and all three survive.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0.48f, 0.87727f, 0f)
        val embS = floatArrayOf(0.53f, 0.285f, 0.79867f)
        val samples = rep(0L, 3, embA) + rep(5000L, 3, embB) +
            listOf(sample(10000L, embS), sample(10200L, embS))
        val clusters = Clusterer().cluster(samples)
        assertEquals(3, clusters.size)
        assertTrue(clusters.any { it.size == 3 })
        assertTrue(clusters.any { it.size == 2 })
    }

    @Test
    fun `contested rescue drops instead of absorbing`() {
        // The fragment matches A at 0.60 but B at 0.58: too close to
        // call, so all three faces drop instead of joining A. Time
        // overlap blocks both merges; alternations stay under the bar.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0f, 1f, 0f)
        val embS = floatArrayOf(0.60f, 0.58f, 0.551f)
        val bigA = listOf(0L, 200L, 400L, 600L, 1400L, 1600L).map { sample(it, embA) }
        val bigB = listOf(100L, 300L, 500L, 2000L).map { sample(it, embB) }
        val frag = listOf(800L, 1000L, 1200L).map { sample(it, embS) }
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(bigA + bigB + frag) { logs.add(it) }
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 6 })
        assertTrue(clusters.any { it.size == 4 })
        assertEquals(3, logs.count { it.startsWith("dissolve-drop") })
    }

    @Test
    fun `fission splits a bimodal cluster`() {
        val members = rep(0L, 6, floatArrayOf(1f, 0f, 0f)) +
            rep(5000L, 6, floatArrayOf(0.3f, 0.9539f, 0f))
        val logs = mutableListOf<String>()
        val halves = Clusterer().splitMixed(members) { logs.add(it) }
        assertEquals(2, halves.size)
        assertTrue(halves.all { it.size == 6 })
        assertTrue(logs.any { it.startsWith("fission") })
    }

    @Test
    fun `fission keeps a pure cluster whole`() {
        val members = rep(0L, 12, floatArrayOf(1f, 0f, 0f))
        val logs = mutableListOf<String>()
        val halves = Clusterer().splitMixed(members) { logs.add(it) }
        assertEquals(1, halves.size)
        assertEquals(12, halves[0].size)
        assertTrue(logs.none { it.startsWith("fission") })
    }

    @Test
    fun `fission keeps a cluster with a tiny half`() {
        val members = rep(0L, 4, floatArrayOf(1f, 0f, 0f)) +
            rep(5000L, 2, floatArrayOf(0.3f, 0.9539f, 0f))
        val halves = Clusterer().splitMixed(members)
        assertEquals(1, halves.size)
        assertEquals(6, halves[0].size)
    }

    @Test
    fun `fission splits sub-merge-bar join pollution end to end`() {
        // Four same-identity faces clear the join floor uncontested and
        // pollute the cluster — below the merge bar, so only fission can
        // separate them again.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0.52f, 0.85416f, 0f)
        val samples = rep(0L, 6, embA) +
            listOf(2000L, 2200L, 2400L, 2600L).map { sample(it, embB) }
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(samples) { logs.add(it) }
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 6 })
        assertTrue(clusters.any { it.size == 4 })
        assertTrue(logs.any { it.startsWith("fission") })
    }

    @Test
    fun `dissolve survives high-index established clusters`() {
        // Two droppable fragments sit below a late established cluster:
        // each removal shifts the live list, so pre-removal indices into
        // it go stale. Addressing the shrunk list with the old index
        // crashed (Index 3 on length 3); references stay valid.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embF1 = floatArrayOf(0.3f, 0.9539f, 0f)
        val embF2 = floatArrayOf(0.3f, -0.9539f, 0f)
        val embB = floatArrayOf(0f, 0f, 1f)
        val samples = rep(0L, 6, embA) +
            listOf(sample(200L, embF1), sample(400L, embF1)) +
            listOf(sample(600L, embF2), sample(800L, embF2)) +
            rep(5000L, 6, embB)
        val logs = mutableListOf<String>()
        val clusters = Clusterer().cluster(samples) { logs.add(it) }
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.size == 6 })
        assertEquals(4, logs.count { it.startsWith("dissolve-drop") })
    }

    @Test
    fun `clear best match still joins directly`() {
        // The margin only reroutes contested faces: s at 0.99 vs 0.11
        // joins A without seeding.
        val embA = floatArrayOf(1f, 0f, 0f)
        val embB = floatArrayOf(0f, 1f, 0f)
        val embS = floatArrayOf(0.99388f, 0.11043f, 0f)
        val samples = rep(0L, 3, embA) + rep(5000L, 3, embB) +
            listOf(sample(10000L, embS))
        val clusters = Clusterer().cluster(samples)
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 4 })
        assertTrue(clusters.any { it.size == 3 })
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
        val trio = rep(0L, 3, floatArrayOf(1f, 0f, 0f))
        val stray = sample(60_000L, floatArrayOf(-0.5774f, -0.5774f, -0.5774f))
        val clusters = Clusterer().cluster(trio + stray)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `non-finite embeddings never seed or poison`() {
        val trio = rep(0L, 3, floatArrayOf(1f, 0f, 0f))
        val nan = sample(5000L, floatArrayOf(Float.NaN, 0f, 0f))
        val clusters = Clusterer().cluster(trio + nan)
        // Trio + NaN singleton: only 2 candidates, below the prune floor,
        // so both survive — but the NaN must not have joined the trio.
        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.size == 3 })
    }

    @Test
    fun `empty input gives empty output`() {
        assertTrue(Clusterer().cluster(emptyList()).isEmpty())
    }

    @Test
    fun `clusters sorted by first appearance`() {
        val late = rep(30_000L, 3, floatArrayOf(1f, 0f, 0f))
        val early = rep(1000L, 3, floatArrayOf(0f, 1f, 0f))
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
        // 0,200,600 bridged (gaps under 1500ms); 3000+ is a new appearance.
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
        // A+B sharing 10.1–11.5s: per-person segmentation counts each
        // person's shared frames as their own single appearance.
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
