package app.facecard.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.facecard.domain.model.VideoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * One sampled frame. Bitmap is upright (rotation applied) and downscaled
 * for detection (max side [FrameExtractor.DETECTION_MAX_SIDE]).
 * Ownership passes to the collector, which must [Bitmap.recycle] it.
 */
data class RawFrame(
    val index: Int,
    val timestampMs: Long,
    val bitmap: Bitmap,
)

interface FrameExtractor {
    /** Application context (for ContentResolver + asset loading downstream). */
    val appContext: Context

    suspend fun metadata(uri: Uri): VideoMeta

    /** Cold streaming flow — memory-safe for 150+ frames. Runs on Dispatchers.IO. */
    fun frames(
        uri: Uri,
        fps: Int = DEFAULT_FPS,
        maxFrames: Int = MAX_FRAMES,
    ): Flow<RawFrame>

    companion object {
        const val DEFAULT_FPS = 5
        const val MAX_FRAMES = 200
        const val DETECTION_MAX_SIDE = 640

        /** How many frames [frames] will emit for a clip of [durationMs]. */
        fun plan(durationMs: Long, fps: Int = DEFAULT_FPS, maxFrames: Int = MAX_FRAMES): Int {
            if (durationMs <= 0 || fps <= 0) return 0
            return minOf(maxFrames, ((durationMs * fps) / 1000).toInt().coerceAtLeast(1))
        }
    }
}

class MediaMetadataRetrieverFrameExtractor(
    override val appContext: Context,
) : FrameExtractor {

    override suspend fun metadata(uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            VideoMeta(
                uri = uri.toString(),
                displayName = displayName(uri),
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0,
                rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )?.toIntOrNull() ?: 0,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun frames(uri: Uri, fps: Int, maxFrames: Int): Flow<RawFrame> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            val total = FrameExtractor.plan(durationMs, fps, maxFrames)
            val stepUs = 1_000_000L / fps
            for (i in 0 until total) {
                currentCoroutineContext().ensureActive()
                val tsUs = i * stepUs
                val raw = retriever.getFrameAtTime(
                    tsUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                emit(RawFrame(i, tsUs / 1000, uprightAndDownscale(raw, rotation)))
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun displayName(uri: Uri): String {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    /**
     * Applies rotation and downscales to detection size.
     * Takes ownership of [src]; returns a new bitmap the caller must recycle.
     */
    private fun uprightAndDownscale(src: Bitmap, rotation: Int): Bitmap {
        val rotated = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } else {
            src
        }
        val scale = (FrameExtractor.DETECTION_MAX_SIDE.toFloat() /
            max(rotated.width, rotated.height)).coerceAtMost(1f)
        return if (scale < 1f) {
            val out = Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (rotated !== src) rotated.recycle()
            out
        } else {
            if (rotated !== src) src.recycle()
            rotated
        }
    }
}
