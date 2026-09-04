package app.facecard.data.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurEstimatorTest {

    @Test
    fun `uniform image has zero variance`() {
        val gray = IntArray(10 * 10) { 128 }
        assertEquals(0.0, BlurEstimator.varianceOfLaplacian(gray, 10, 10), 1e-9)
    }

    @Test
    fun `too-small image returns zero`() {
        assertEquals(0.0, BlurEstimator.varianceOfLaplacian(IntArray(4) { 255 }, 2, 2), 1e-9)
    }

    @Test
    fun `checkerboard scores far above flat gray`() {
        val sharp = IntArray(16 * 16) { i ->
            val x = i % 16
            val y = i / 16
            if ((x + y) % 2 == 0) 0 else 255
        }
        val flat = IntArray(16 * 16) { 128 }
        val sharpVar = BlurEstimator.varianceOfLaplacian(sharp, 16, 16)
        val flatVar = BlurEstimator.varianceOfLaplacian(flat, 16, 16)
        assertTrue("sharp=$sharpVar flat=$flatVar", sharpVar > 1000.0 * (flatVar + 1.0))
    }

    @Test
    fun `box blur lowers variance (motion-blur analogue)`() {
        val w = 24
        val h = 24
        // Vertical edge: left half dark, right half bright.
        val sharp = IntArray(w * h) { i -> if (i % w < w / 2) 0 else 255 }
        // 3x3 box blur of the same image.
        val blurred = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) {
                    sum += sharp[ny * w + nx]
                    n++
                }
            }
            sum / n
        }
        val sharpVar = BlurEstimator.varianceOfLaplacian(sharp, w, h)
        val blurredVar = BlurEstimator.varianceOfLaplacian(blurred, w, h)
        assertTrue("sharp=$sharpVar blurred=$blurredVar", sharpVar > blurredVar * 2)
    }

    @Test
    fun `sharp checkerboard passes frame threshold, flat fails`() {
        val sharp = IntArray(16 * 16) { i ->
            if (((i % 16) + (i / 16)) % 2 == 0) 0 else 255
        }
        assertTrue(
            BlurEstimator.varianceOfLaplacian(sharp, 16, 16) >= BlurEstimator.FRAME_MIN_VARIANCE,
        )
        assertFalse(
            BlurEstimator.varianceOfLaplacian(IntArray(16 * 16) { 128 }, 16, 16) >=
                BlurEstimator.FRAME_MIN_VARIANCE,
        )
    }
}

class DetectedFaceTest {

    private fun face(l: Int, t: Int, r: Int, b: Int) = DetectedFace(
        left = l, top = t, right = r, bottom = b,
        eulerY = 0f, eulerZ = 0f,
        leftEyeOpen = 0.9f, rightEyeOpen = 0.9f, smiling = 0.5f,
        trackingId = null, edgeClipped = false,
    )

    @Test
    fun `largest keeps top N by area, biggest first`() {
        val faces = listOf(
            face(0, 0, 10, 10), // 100
            face(0, 0, 50, 50), // 2500
            face(0, 0, 20, 20), // 400
        )
        val top2 = faces.largest(2)
        assertEquals(2, top2.size)
        assertEquals(2500, top2[0].area)
        assertEquals(400, top2[1].area)
    }

    @Test
    fun `largest defaults to six`() {
        val faces = List(10) { i -> face(0, 0, 10 + i, 10 + i) }
        assertEquals(6, faces.largest().size)
    }

    @Test
    fun `centered face is not clipped`() {
        assertFalse(isEdgeClipped(200, 300, 400, 600, 640, 1136))
    }

    @Test
    fun `face touching frame edge is clipped`() {
        assertTrue(isEdgeClipped(0, 300, 200, 600, 640, 1136))
        assertTrue(isEdgeClipped(200, 300, 640, 600, 640, 1136))
        assertTrue(isEdgeClipped(200, 0, 400, 300, 640, 1136))
    }

    @Test
    fun `invalid frame dims count as clipped`() {
        assertTrue(isEdgeClipped(10, 10, 50, 50, 0, 0))
    }
}
