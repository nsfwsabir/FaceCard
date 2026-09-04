package app.facecard.domain.pipeline

import app.facecard.data.video.FrameExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineProgressTest {

    @Test
    fun `extract stage maps to 0 to 20 percent`() {
        assertEquals(0f, PipelineProgress.overall(PipelineStage.EXTRACT, 0f), 1e-4f)
        assertEquals(0.10f, PipelineProgress.overall(PipelineStage.EXTRACT, 0.5f), 1e-4f)
        assertEquals(0.20f, PipelineProgress.overall(PipelineStage.EXTRACT, 1f), 1e-4f)
    }

    @Test
    fun `detect stage maps to 20 to 55 percent`() {
        assertEquals(0.20f, PipelineProgress.overall(PipelineStage.DETECT, 0f), 1e-4f)
        assertEquals(0.55f, PipelineProgress.overall(PipelineStage.DETECT, 1f), 1e-4f)
    }

    @Test
    fun `collage complete is 100 percent`() {
        assertEquals(1f, PipelineProgress.overall(PipelineStage.COLLAGE, 1f), 1e-4f)
    }

    @Test
    fun `fractions clamp to 0-1`() {
        assertEquals(0f, PipelineProgress.overall(PipelineStage.EXTRACT, -2f), 1e-4f)
        assertEquals(0.20f, PipelineProgress.overall(PipelineStage.EXTRACT, 5f), 1e-4f)
    }

    @Test
    fun `30s clip at 5fps plans 150 frames`() {
        assertEquals(150, FrameExtractor.plan(30_000))
    }

    @Test
    fun `plan caps at max frames and floors at one`() {
        assertEquals(200, FrameExtractor.plan(120_000))
        assertEquals(1, FrameExtractor.plan(100))
        assertEquals(0, FrameExtractor.plan(0))
        assertEquals(0, FrameExtractor.plan(-5))
    }
}
