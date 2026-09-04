package app.facecard.domain.pipeline

import app.facecard.domain.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {

    private fun person(id: Int, label: String, ts: List<Long>, emb: FloatArray): Person {
        val samples = ts.map { hintSample(it, emb) }
        return Person(
            id = id, label = label, samples = samples,
            appearances = AppearanceSegmenter.segment(samples),
            best = QualityScorer.best(samples),
        )
    }

    @Test
    fun `mergeMembers unions samples and recomputes everything`() {
        val keep = person(0, "Person A", listOf(0L, 200L, 400L), floatArrayOf(1f, 0f, 0f))
        val dropSamples = listOf(5000L, 5200L, 5400L)
            .map {
                hintSample(it, floatArrayOf(0.99f, 0.141f, 0f)).copy(sharpness = 900.0)
            }
        val drop = Person(
            id = 1, label = "Person B", samples = dropSamples,
            appearances = AppearanceSegmenter.segment(dropSamples),
            best = QualityScorer.best(dropSamples),
        )
        val merged = mergeMembers(keep, drop)
        assertEquals(0, merged.id)
        assertEquals("Person A", merged.label)
        assertEquals(6, merged.samples.size)
        assertEquals(2, merged.appearances.size)
        // Best re-picked across the union: the sharper drop-side sample wins.
        assertEquals(900.0, merged.best.sharpness, 1e-9)
    }

    @Test
    fun `hint fires for non-overlapping mid-similarity pair`() {
        val a = person(0, "Person A", listOf(0L, 200L, 400L), floatArrayOf(1f, 0f, 0f))
        val b = person(
            1, "Person B", listOf(5000L, 5200L, 5400L),
            floatArrayOf(0.5f, 0.866f, 0f),
        )
        val hints = computeDupHints(listOf(a, b))
        assertEquals(1, hints.size)
        assertEquals(0, hints[0].aId)
        assertEquals(1, hints[0].bId)
        assertTrue("sim=${hints[0].sim}", hints[0].sim in 0.45f..0.58f)
    }

    @Test
    fun `no hint when pair shares screen time`() {
        val a = person(0, "Person A", listOf(0L, 200L, 400L), floatArrayOf(1f, 0f, 0f))
        val b = person(
            1, "Person B", listOf(100L, 300L, 500L),
            floatArrayOf(0.5f, 0.866f, 0f),
        )
        assertTrue(computeDupHints(listOf(a, b)).isEmpty())
    }

    @Test
    fun `no hint for clearly distinct people`() {
        val a = person(0, "Person A", listOf(0L, 200L, 400L), floatArrayOf(1f, 0f, 0f))
        val b = person(
            1, "Person B", listOf(5000L, 5200L, 5400L),
            floatArrayOf(-0.5774f, -0.5774f, -0.5774f),
        )
        assertTrue(computeDupHints(listOf(a, b)).isEmpty())
    }

    @Test
    fun `no hint above the auto-merge bar`() {
        // Identical embeddings apart in time: the pipeline merges these
        // itself, so no human hint is needed.
        val a = person(0, "Person A", listOf(0L, 200L, 400L), floatArrayOf(1f, 0f, 0f))
        val b = person(1, "Person B", listOf(5000L, 5200L, 5400L), floatArrayOf(1f, 0f, 0f))
        assertTrue(computeDupHints(listOf(a, b)).isEmpty())
    }
}
