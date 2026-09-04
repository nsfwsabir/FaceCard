package app.facecard.ui.processing

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.facecard.data.face.BlurEstimator
import app.facecard.data.face.MlKitFaceDetector
import app.facecard.data.face.largest
import app.facecard.data.video.FrameExtractor
import app.facecard.domain.pipeline.PipelineStage
import app.facecard.domain.pipeline.PipelineUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Detection counters shown on the Done screen (Phase 5 replaces with people UI). */
data class DetectStats(
    val frames: Int,
    val framesWithFaces: Int,
    val facesTotal: Int,
    val whipPanDrops: Int,
    val blurredFaceDrops: Int,
)

/**
 * Phase 3: two streaming passes over the video —
 * pass 1 EXTRACT (decode + count + thumbnail), pass 2 DETECT (re-decode,
 * blur-gate, ML Kit, per-face blur filter). Re-decoding costs ~1–2s for a
 * 30s clip and keeps peak memory flat (one 640px bitmap at a time) while
 * keeping stage progress honest. Phase 4 chains EMBED onto pass 2.
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

    private val _stats = MutableStateFlow<DetectStats?>(null)
    val stats: StateFlow<DetectStats?> = _stats

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
                // ---- Pass 1: EXTRACT (decode only) ----
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
                    _state.value = PipelineUiState.Running(
                        stage = PipelineStage.EXTRACT,
                        done = count,
                        total = total,
                        etaMs = eta(SystemClock.elapsedRealtime() - t0, count, total),
                    )
                    frame.bitmap.recycle()
                }
                // ---- Pass 2: DETECT (re-decode + blur gate + ML Kit) ----
                val detector = MlKitFaceDetector()
                try {
                    var framesWithFaces = 0
                    var facesTotal = 0
                    var whipDrops = 0
                    var blurredFaces = 0
                    var d = 0
                    val t1 = SystemClock.elapsedRealtime()
                    extractor.frames(uri).collect { frame ->
                        currentCoroutineContext().ensureActive()
                        d++
                        val bmp = frame.bitmap
                        if (BlurEstimator.sharpnessOf(bmp) < BlurEstimator.FRAME_MIN_VARIANCE) {
                            whipDrops++
                        } else {
                            val faces = withContext(Dispatchers.Default) {
                                detector.detect(bmp)
                            }.largest()
                            var kept = 0
                            for (f in faces) {
                                val region = Rect(f.left, f.top, f.right, f.bottom)
                                if (BlurEstimator.sharpnessOf(bmp, region, 64) <
                                    BlurEstimator.FACE_MIN_VARIANCE
                                ) {
                                    blurredFaces++
                                } else {
                                    kept++
                                }
                            }
                            if (kept > 0) framesWithFaces++
                            facesTotal += kept
                            // Phase 4: embed each kept face here, before recycle.
                        }
                        _state.value = PipelineUiState.Running(
                            stage = PipelineStage.DETECT,
                            done = d,
                            total = total,
                            etaMs = eta(SystemClock.elapsedRealtime() - t1, d, total),
                        )
                        bmp.recycle()
                    }
                    _stats.value = DetectStats(
                        frames = count,
                        framesWithFaces = framesWithFaces,
                        facesTotal = facesTotal,
                        whipPanDrops = whipDrops,
                        blurredFaceDrops = blurredFaces,
                    )
                } finally {
                    detector.close()
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

    private fun eta(elapsedMs: Long, done: Int, total: Int): Long? =
        if (done >= 3 && done < total) elapsedMs * (total - done) / done else null

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
