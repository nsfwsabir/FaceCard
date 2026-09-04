package app.facecard.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.facecard.domain.pipeline.CollageLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One tile's content. Bitmaps are borrowed (not recycled) by [render]. */
data class TileInput(
    val label: String,
    val sublabel: String,
    val bitmap: Bitmap,
)

/**
 * Renders the 1080×1920 story collage (design.md §5).
 * The returned bitmap IS the export file content; the Compose preview
 * displays this same bitmap — preview == export by construction.
 */
class CollageRenderer {

    fun render(
        videoName: String,
        dateMs: Long,
        footer: String,
        tiles: List<TileInput>,
    ): Bitmap {
        require(tiles.isNotEmpty())
        val W = CollageLayout.W
        val H = CollageLayout.H
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        // Plum gradient background.
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bgPaint)

        // Header.
        c.drawText("facecard", 60f, 150f, wordmarkPaint)
        val date = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(dateMs))
        c.drawText("$videoName · $date", 62f, 210f, subtitlePaint)

        // Tiles.
        val layout = CollageLayout.computeLayout(tiles.size)
        for (i in tiles.indices) {
            val t = tiles[i]
            val r = RectF(
                layout[i].x * W,
                layout[i].y * H,
                (layout[i].x + layout[i].w) * W,
                (layout[i].y + layout[i].h) * H,
            )
            drawTile(c, t, r)
        }

        // Footer pill.
        val fw = footerPaint.measureText(footer) + 96f
        val pill = RectF((W - fw) / 2f, 1746f, (W + fw) / 2f, 1842f)
        c.drawRoundRect(pill, 48f, 48f, pillPaint)
        c.drawText(footer, W / 2f, 1808f, footerPaint)

        return out
    }

    private fun drawTile(c: Canvas, t: TileInput, r: RectF) {
        val bmp = t.bitmap
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return
        c.save()
        val path = Path().apply { addRoundRect(r, TILE_RADIUS, TILE_RADIUS, Path.Direction.CW) }
        c.clipPath(path)

        // Center-crop the tile crop into the tile rect.
        val scale = maxOf(r.width() / bmp.width, r.height() / bmp.height)
        val sw = r.width() / scale
        val sh = r.height() / scale
        val sx = (bmp.width - sw) / 2f
        val sy = (bmp.height - sh) / 2f
        val src = Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        c.drawBitmap(bmp, src, r, bitmapPaint)

        // Bottom scrim for chip legibility (≥4.5:1 for white text).
        val scrimTop = r.bottom - r.height() * 0.38f
        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, scrimTop, 0f, r.bottom,
                intArrayOf(0x00000000, 0xA6000000.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(r.left, scrimTop, r.right, r.bottom, scrim)

        // Label chip.
        val label = "${t.label} · ${t.sublabel}"
        val tw = chipPaint.measureText(label)
        val chip = RectF(
            r.left + 24f, r.bottom - 88f,
            r.left + 24f + tw + 56f, r.bottom - 24f,
        )
        // Clamp chip inside narrow tiles.
        val chipClamped = RectF(
            chip.left, chip.top,
            minOf(chip.right, r.right - 16f), chip.bottom,
        )
        c.drawRoundRect(chipClamped, 32f, 32f, chipBgPaint)
        c.drawText(label, chipClamped.left + 28f, chipClamped.bottom - 20f, chipPaint)

        c.restore()
        // Hairline stroke for separation on busy backgrounds.
        c.drawRoundRect(r, TILE_RADIUS, TILE_RADIUS, strokePaint)
    }

    companion object {
        const val TILE_RADIUS = 28f

        private val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, CollageLayout.H.toFloat(),
                intArrayOf(0xFF1B1025.toInt(), 0xFF3B1D5A.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        private val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEBFFFFFF.toInt()
            textSize = 92f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt()
            textSize = 44f
        }
        private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEBFFFFFF.toInt()
            textSize = 46f
            textAlign = Paint.Align.CENTER
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x524B4B4B.toInt()
        }
        private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEBEADDFF.toInt()
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4F378B.toInt()
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x14FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
    }
}
