package app.facecard.ui.processing

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.facecard.data.video.FrameExtractor
import app.facecard.domain.pipeline.PipelineStage
import app.facecard.domain.pipeline.PipelineUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Phase 2: runs EXTRACT over a streaming [FrameExtractor] flow with progress,
 * ETA, cancel, and rotation safety (ViewModel survives config change).
 * Phase 3 chains DETECT onto each frame before recycle; later phases append
 * EMBED → CLUSTER → SCORE → COLLAGE into the same [state] stream.
 */
class ProcessingViewModel(
    private val videoUri: Uri?,
    private val extractor: FrameExtractor,
) : ViewModel() {

    private val _state = MutableStateFlow<PipelineUiState>(PipelineUiState.Idle)
    val state: StateFlow<PipelineUiState> = _state

    /** Small upright thumbnail (first frame) for the Done screen. */
    private val _thumbnail = MutableStateFlow<Bitmap?>(null)
    val thumbnail: StateFlow<Bitmap?> = _thumbnail

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val uri = videoUri
        if (uri == null) {
            _state.value = PipelineUiState.Error("No video selected", PipelineStage.EXTRACT)
            return
        }
        job = viewModelScope.launch {
            try {
                val meta = extractor.metadata(uri)
                if (meta.durationMs <= 0) {
                    throw IllegalArgumentException("Couldn't read this video (duration unknown)")
                }
                val total = FrameExtractor.plan(meta.durationMs)
                if (total == 0) {
                    throw IllegalArgumentException("Couldn't plan frame sampling for this video")
                }
                var count = 0
                val t0 = SystemClock.elapsedRealtime()
                extractor.frames(uri).collect { frame ->
                    currentCoroutineContext().ensureActive()
                    count++
                    if (_thumbnail.value == null) {
                        _thumbnail.value = Bitmap.createScaledBitmap(
                            frame.bitmap,
                            180,
                            (180f * frame.bitmap.height / frame.bitmap.width).toInt()
                                .coerceAtLeast(1),
                            true,
                        )
                    }
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    val eta = if (count >= 3 && count < total) {
                        elapsed * (total - count) / count
                    } else {
                        null
                    }
                    // Phase 3: ML Kit detect goes here, before recycle.
                    _state.value = PipelineUiState.Running(
                        stage = PipelineStage.EXTRACT,
                        done = count,
                        total = total,
                        etaMs = eta,
                    )
                    frame.bitmap.recycle()
                }
                _state.value = PipelineUiState.Done(meta.copy(frameCount = count))
            } catch (e: CancellationException) {
                _state.value = PipelineUiState.Cancelled
                throw e
            } catch (e: Exception) {
                _state.value = PipelineUiState.Error(
                    e.message ?: "Processing failed",
                    PipelineStage.EXTRACT,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    override fun onCleared() {
        job?.cancel()
        _thumbnail.value?.recycle()
        super.onCleared()
    }
}

class ProcessingViewModelFactory(
    private val uri: Uri?,
    private val extractor: FrameExtractor,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProcessingViewModel(uri, extractor) as T
    }
}
