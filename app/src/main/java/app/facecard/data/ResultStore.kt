package app.facecard.data

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import app.facecard.domain.model.ProcessResult
import app.facecard.domain.model.VideoMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    @Synchronized
    fun set(
        result: ProcessResult,
        thumbs: Map<Int, Bitmap>,
        meta: VideoMeta,
        uri: Uri,
    ) {
        _thumbs.value.values.forEach { runCatching { it.recycle() } }
        _result.value = result
        _thumbs.value = thumbs
        _meta.value = meta
        _videoUri.value = uri
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
