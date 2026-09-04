package app.facecard.data.face

import android.graphics.Bitmap
import kotlin.math.max

/**
 * No-OpenCV sharpness gate. Laplacian variance on a downscaled grayscale
 * image: high variance = crisp edges, low = blur / whip-pan smear.
 *
 * This is THE whip-pan killer (PRD counting contract: blurred passes count
 * for nobody). Frames below [FRAME_MIN_VARIANCE] are dropped before counting;
 * faces below [FACE_MIN_VARIANCE] are dropped from counting AND best shots.
 *
 * Array math is pure Kotlin ([varianceOfLaplacian]) so thresholds are
 * JVM-unit-tested; only the Bitmap→gray glue needs a device.
 */
object BlurEstimator {

    /** Below this, a face region is motion-blurred — exclude it. */
    const val FACE_MIN_VARIANCE = 60.0

    /** Below this, the whole frame is a whip-pan smear — drop the frame. */
    const val FRAME_MIN_VARIANCE = 40.0

    /**
     * Sharpness of [bitmap] (or [region] of it). Downscales to [maxSide]
     * first so per-frame cost stays ~ms on mid CPUs.
     */
    fun sharpnessOf(
        bitmap: Bitmap,
        region: android.graphics.Rect? = null,
        maxSide: Int = 160,
    ): Double {
        val src = if (region != null) {
            val l = region.left.coerceIn(0, bitmap.width - 1)
            val t = region.top.coerceIn(0, bitmap.height - 1)
            val r = region.right.coerceIn(l + 1, bitmap.width)
            val b = region.bottom.coerceIn(t + 1, bitmap.height)
            Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
        } else {
            bitmap
        }
        val scale = (maxSide.toFloat() / max(src.width, src.height)).coerceAtMost(1f)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        val (gray, w, h) = toGray(small)
        val v = varianceOfLaplacian(gray, w, h)
        if (small !== src && small !== bitmap) small.recycle()
        if (src !== bitmap) src.recycle()
        return v
    }

    /** Row-major grayscale pixels + dimensions. */
    fun toGray(bitmap: Bitmap): Triple<IntArray, Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            px[i] = ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt()
        }
        return Triple(px, w, h)
    }

    /**
     * Variance of the 3×3 Laplacian response over interior pixels.
     * Kernel: 0 1 0 / 1 -4 1 / 0 1 0.
     */
    fun varianceOfLaplacian(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3 || gray.size < w * h) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = gray[y * w + x]
                val lap = (gray[(y - 1) * w + x] + gray[(y + 1) * w + x] +
                    gray[y * w + (x - 1)] + gray[y * w + (x + 1)] - 4 * c).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }
}
