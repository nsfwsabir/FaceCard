package app.facecard.domain.pipeline

/**
 * Single source of truth for collage geometry (TRD §4.9).
 * Normalised 0..1 rects on the 1080×1920 story canvas; BOTH the Compose
 * preview and the export renderer consume these, so preview == export.
 *
 * Zones: header 0–260px, content 300–1660px, footer 1700–1920px.
 * Margin 48px, gaps 24px (design.md §5).
 */
data class TileRect(val x: Float, val y: Float, val w: Float, val h: Float)

object CollageLayout {
    const val W = 1080
    const val H = 1920

    fun computeLayout(n: Int): List<TileRect> {
        require(n >= 1) { "Need at least one person" }
        return when (n) {
            1 -> listOf(px(48, 420, 984, 1000))
            2 -> listOf(px(48, 320, 984, 620), px(48, 964, 984, 620))
            3 -> listOf(
                px(48, 300, 984, 620),
                px(48, 944, 480, 480),
                px(552, 944, 480, 480),
            )
            4 -> grid2x2(y0 = 300, h = 640)
            5 -> listOf(px(48, 300, 984, 540)) + grid2x2(y0 = 864, h = 380)
            6 -> grid(rows = 3, y0 = 300, h = 440)
            else -> {
                // 7, 8 (or more): 2-col rows filling the content zone.
                val rows = (n + 1) / 2
                val h = ((1360 - (rows - 1) * 24) / rows).coerceAtLeast(200)
                grid(rows, y0 = 300, h)
            }
        }.take(n)
    }

    private fun grid2x2(y0: Int, h: Int): List<TileRect> = listOf(
        px(48, y0, 480, h),
        px(552, y0, 480, h),
        px(48, y0 + h + 24, 480, h),
        px(552, y0 + h + 24, 480, h),
    )

    private fun grid(rows: Int, y0: Int, h: Int): List<TileRect> {
        val out = mutableListOf<TileRect>()
        for (r in 0 until rows) {
            val y = y0 + r * (h + 24)
            out.add(px(48, y, 480, h))
            out.add(px(552, y, 480, h))
        }
        return out
    }

    private fun px(x: Int, y: Int, w: Int, h: Int) =
        TileRect(x / W.toFloat(), y / H.toFloat(), w / W.toFloat(), h / H.toFloat())
}
