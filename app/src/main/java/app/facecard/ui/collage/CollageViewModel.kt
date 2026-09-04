package app.facecard.ui.collage

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.facecard.data.export.CollageRenderer
import app.facecard.data.export.TileInput
import app.facecard.data.export.generousCrop
import app.facecard.data.video.FrameExtractor
import app.facecard.domain.model.ProcessResult
import app.facecard.domain.model.VideoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders the story collage once, off the main thread. The output bitmap
 * is shown in the preview AND written to disk on save — parity guaranteed.
 * Full-res frames are decoded one at a time and recycled immediately.
 */
class CollageViewModel(
    private val videoUri: Uri,
    private val meta: VideoMeta,
    private val result: ProcessResult,
    private val extractor: FrameExtractor,
) : ViewModel() {

    sealed interface Ui {
        data object Idle : Ui
        data object Rendering : Ui
        data class Done(val bitmap: Bitmap) : Ui
        data class Error(val message: String) : Ui
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Idle)
    val ui: StateFlow<Ui> = _ui

    private var job: Job? = null

    fun render() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _ui.value = Ui.Rendering
            try {
                val tiles = withContext(Dispatchers.Default) {
                    result.people.map { p ->
                        currentCoroutineContext().ensureActive()
                        val full = extractor.frameAt(videoUri, p.best.tsMs)
                            ?: throw IllegalStateException(
                                "Couldn't re-read frame for ${p.label}",
                            )
                        val crop = generousCrop(p.best, full.width, full.height)
                        val tile = Bitmap.createBitmap(full, crop.l, crop.t, crop.w, crop.h)
                        full.recycle()
                        TileInput(
                            label = p.label,
                            sublabel = "×${p.appearanceCount}",
                            bitmap = tile,
                        )
                    }
                }
                val bitmap = withContext(Dispatchers.Default) {
                    CollageRenderer().render(
                        videoName = meta.displayName,
                        dateMs = System.currentTimeMillis(),
                        footer = "${result.personCount} people · " +
                            "${result.totalAppearances} appearances · on-device",
                        tiles = tiles,
                    )
                }
                tiles.forEach { runCatching { it.bitmap.recycle() } }
                (_ui.value as? Ui.Done)?.bitmap?.recycle()
                _ui.value = Ui.Done(bitmap)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.value = Ui.Error(e.message ?: "Couldn't build the collage")
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
        (_ui.value as? Ui.Done)?.bitmap?.recycle()
        super.onCleared()
    }
}

class CollageViewModelFactory(
    private val uri: Uri,
    private val meta: VideoMeta,
    private val result: ProcessResult,
    private val extractor: FrameExtractor,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return CollageViewModel(uri, meta, result, extractor) as T
    }
}
