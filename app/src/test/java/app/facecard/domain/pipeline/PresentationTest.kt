package app.facecard.domain.pipeline

import app.facecard.data.export.CropRect
import app.facecard.data.export.generousCrop
import app.facecard.domain.model.FaceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QualityScorerTest {

    private fun sample(
        eulerY: Float = 0f,
        eulerZ: Float = 0f,
        sharpness: Double = 500.0,
        eyeOpen: Float = 0.9f,
        smiling: Float = 0.8f,
        edgeClipped: Boolean = false,
        area: Int = 40_000,
    ) = FaceSample(
        tsMs = 0L, embedding = floatArrayOf(1f, 0f), sharpness = sharpness,
        eulerY = eulerY, eulerZ = eulerZ, eyeOpen = eyeOpen, smiling = smiling,
        edgeClipped = edgeClipped, area = area, trackingId = null,
        left = 100, top = 100, right = 300, bottom = 300,
        frameW = 640, frameH = 1136,
    )

    @Test
    fun `flattering shot beats poor shot`() {
        val good = QualityScorer.score(sample()).total
        val bad = QualityScorer.score(
            sample(
                eulerY = 30f, sharpness = 70.0, eyeOpen = 0.3f,
                smiling = 0.2f, edgeClipped = true, area = 5_000,
            ),
        ).total
        assertTrue("good=$good bad=$bad", good > bad * 2)
    }

    @Test
    fun `closed eyes veto crushes score`() {
        val open = QualityScorer.score(sample(eyeOpen = 0.9f)).total
        val closed = QualityScorer.score(sample(eyeOpen = 0.1f)).total
        assertTrue("open=$open closed=$closed", closed < open * 0.4f)
    }

    @Test
    fun `edge clip penalty applies`() {
        val clean = QualityScorer.score(sample()).total
        val clipped = QualityScorer.score(sample(edgeClipped = true)).total
        assertTrue(abs(clipped - clean * 0.3f) < 1e-4f)
    }

    @Test
    fun `profile veto applies past 35 degrees`() {
        val frontal = QualityScorer.score(sample(eulerY = 5f)).total
        val profile = QualityScorer.score(sample(eulerY = 40f)).total
        assertTrue("frontal=$frontal profile=$profile", profile < frontal * 0.6f)
    }

    @Test
    fun `best picks argmax deterministically`() {
        val samples = listOf(
            sample(sharpness = 100.0),
            sample(sharpness = 500.0),
            sample(sharpness = 300.0),
        )
        assertEquals(500.0, QualityScorer.best(samples).sharpness, 1e-9)
    }
}

class CollageLayoutTest {

    private fun overlap(a: TileRect, b: TileRect): Float {
        val ix = (minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)).coerceAtLeast(0f)
        val iy = (minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return ix * iy
    }

    @Test
    fun `every person appears exactly once, no overlaps, in bounds`() {
        for (n in 1..8) {
            val tiles = CollageLayout.computeLayout(n)
            assertEquals("n=$n", n, tiles.size)
            for (t in tiles) {
                assertTrue("n=$n tile=$t", t.x >= 0 && t.y >= 0 &&
                    t.x + t.w <= 1.001f && t.y + t.h <= 1.001f)
                assertTrue("n=$n tile=$t", t.w > 0.05f && t.h > 0.05f)
            }
            for (i in tiles.indices) for (j in i + 1 until tiles.size) {
                assertTrue(
                    "n=$n overlap ${tiles[i]} vs ${tiles[j]}",
                    overlap(tiles[i], tiles[j]) < 1e-6f,
                )
            }
        }
    }

    @Test
    fun `tiles avoid header and footer zones`() {
        for (n in 1..8) {
            for (t in CollageLayout.computeLayout(n)) {
                val topPx = t.y * CollageLayout.H
                val botPx = (t.y + t.h) * CollageLayout.H
                assertTrue("n=$n top=$topPx", topPx >= 260f)
                assertTrue("n=$n bottom=$botPx", botPx <= 1700f)
            }
        }
    }
}

class TileCropperTest {

    private fun sample() = FaceSample(
        tsMs = 0L, embedding = floatArrayOf(1f, 0f), sharpness = 500.0,
        eulerY = 0f, eulerZ = 0f, eyeOpen = 0.9f, smiling = 0.5f,
        edgeClipped = false, area = 10_000, trackingId = null,
        left = 270, top = 400, right = 370, bottom = 500, // 100×100 in 640×1136
        frameW = 640, frameH = 1136,
    )

    @Test
    fun `crop is generous, clamped, and maps to full res`() {
        // Full-res 2× detection scale: box maps to 200×200.
        val c: CropRect = generousCrop(sample(), 1280, 2272)
        // 2.4×200 = 480, but the 45%-of-short-side floor (≈576) wins.
        assertEquals(c.w, c.h)
        assertTrue("w=${c.w}", c.w >= 570)
        assertTrue(c.l >= 0 && c.t >= 0)
        assertTrue(c.l + c.w <= 1280 && c.t + c.h <= 2272)
        // Box centre (640, 900 pre-headroom) sits inside the crop.
        assertTrue(c.l < 640 && 640 < c.l + c.w)
    }

    @Test
    fun `degenerate input falls back to full frame`() {
        val c = generousCrop(sample().copy(frameW = 0), 1080, 1920)
        assertEquals(CropRect(0, 0, 1080, 1920), c)
    }

    @Test
    fun `corner face still yields in-frame crop`() {
        val s = sample().copy(left = 0, top = 0, right = 60, bottom = 60)
        val c = generousCrop(s, 640, 1136)
        assertTrue(c.l >= 0 && c.t >= 0 && c.l + c.w <= 640 && c.t + c.h <= 1136)
        assertTrue(c.w >= 200)
    }
}
