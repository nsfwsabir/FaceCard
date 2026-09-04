package app.facecard.data

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import app.facecard.data.export.generousCrop
import app.facecard.data.video.FrameExtractor
import app.facecard.domain.model.Person
import app.facecard.domain.model.ProcessResult
import app.facecard.domain.model.VideoMeta
import app.facecard.domain.pipeline.DupHint
import app.facecard.domain.pipeline.computeDupHints
import app.facecard.domain.pipeline.mergeMembers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class FaceCardApp : Application() {
    val resultStore = ResultStore()
}

/**
 * Application-scoped holder for the latest pipeline output.
 * Lets Results/Collage screens read people, thumbs, and source video
 * without fragile nav-arg parceling of bitmaps. Thumbnails are recycled
 * on [set] (replaced run) and [clear].
 */
class ResultStore {
    private val _result = MutableStateFlow<ProcessResult?>(null)
    val result: StateFlow<ProcessResult?> = _result

    private val _thumbs = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val thumbs: StateFlow<Map<Int, Bitmap>> = _thumbs

    private val _meta = MutableStateFlow<VideoMeta?>(null)
    val meta: StateFlow<VideoMeta?> = _meta

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri

    private val _hints = MutableStateFlow<List<DupHint>>(emptyList())
    val hints: StateFlow<List<DupHint>> = _hints

    private var extractor: FrameExtractor? = null

    @Synchronized
    fun set(
        result: ProcessResult,
        thumbs: Map<Int, Bitmap>,
        meta: VideoMeta,
        uri: Uri,
        extractor: FrameExtractor,
    ) {
        _thumbs.value.values.forEach { runCatching { it.recycle() } }
        _result.value = result
        _thumbs.value = thumbs
        _meta.value = meta
        _videoUri.value = uri
        this.extractor = extractor
        _hints.value = computeDupHints(result.people)
    }

    /**
     * Human-oracle merge for a [DupHint]: folds [dropId] into [keepId]
     * (members, counts, best shot recomputed), refreshes the surviving
     * thumbnail, and recomputes hints. Returns false when either side is
     * already gone. No similarity bar — the human is the oracle.
     */
    suspend fun mergePeople(keepId: Int, dropId: Int): Boolean =
        withContext(Dispatchers.Default) {
            if (keepId == dropId) return@withContext false
            val res = _result.value ?: return@withContext false
            val keep = res.people.find { it.id == keepId } ?: return@withContext false
            val drop = res.people.find { it.id == dropId } ?: return@withContext false
            val merged = mergeMembers(keep, drop)
            _result.value = ProcessResult(
                res.people.filter { it.id != dropId }.map {
                    if (it.id == keepId) merged else it
                },
            )
            _hints.value = computeDupHints(_result.value!!.people)
            refreshThumb(merged.id, merged)
            val m = _thumbs.value.toMutableMap()
            m.remove(dropId)?.recycle()
            _thumbs.value = m
            true
        }

    /** Re-extracts the best-shot thumb for [personId]; keeps the old on failure. */
    private suspend fun refreshThumb(personId: Int, person: Person) {
        val ext = extractor
        val uri = _videoUri.value
        if (ext == null || uri == null) return
        try {
            ext.frameAt(uri, person.best.tsMs)?.let { full ->
                val crop = generousCrop(
                    person.best, full.width, full.height,
                    shared = !person.best.soloFrame,
                )
                val tile = Bitmap.createBitmap(full, crop.l, crop.t, crop.w, crop.h)
                full.recycle()
                val tw = 360
                val th = (360f * tile.height / tile.width).toInt().coerceAtLeast(1)
                val fresh = Bitmap.createScaledBitmap(tile, tw, th, true)
                tile.recycle()
                val m = _thumbs.value.toMutableMap()
                m[personId]?.recycle()
                m[personId] = fresh
                _thumbs.value = m
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        _thumbs.value.values.forEach { runCatching { it.recycle() } }
        _result.value = null
        _thumbs.value = emptyMap()
        _meta.value = null
        _videoUri.value = null
    }
}
