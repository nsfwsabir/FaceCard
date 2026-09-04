package app.facecard.ui.processing

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.facecard.data.FaceCardApp
import app.facecard.data.export.generousCrop
import app.facecard.data.face.BlurEstimator
import app.facecard.data.face.MlKitFaceDetector
import app.facecard.data.face.TfliteMobileFaceNet
import app.facecard.data.face.largest
import app.facecard.data.face.squareFaceCrop
import app.facecard.data.video.FrameExtractor
import app.facecard.domain.model.FaceSample
import app.facecard.domain.model.Person
import app.facecard.domain.model.ProcessResult
import app.facecard.domain.pipeline.AppearanceSegmenter
import app.facecard.domain.pipeline.Clusterer
import app.facecard.domain.pipeline.PipelineStage
import app.facecard.domain.pipeline.PipelineUiState
import app.facecard.domain.pipeline.QualityScorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Detection counters shown on the Done screen (Phase 5 replaces with people UI). */
data class DetectStats(
    val frames: Int,
    val framesWithFaces: Int,
    val facesTotal: Int,
    val whipPanDrops: Int,
    val blurredFaceDrops: Int,
)

/**
 * Phase 4: two streaming passes over the video, all heavy work confined to
 * Dispatchers.Default (viewModelScope itself is Main — collecting there
 * would jank the progress UI).
 *
 * - Pass 1 EXTRACT: decode + count + thumbnail.
 * - Pass 2 DETECT + EMBED: re-decode, blur-gate, ML Kit, per-face blur
 *   filter, 112px crop → MobileFaceNet → FaceSample. One 640px bitmap
 *   (plus transient 112px crops) alive at a time.
 * - CLUSTER (in-memory, ms): greedy cosine clustering + appearance
 *   segmentation → [ProcessResult] (people + appearance counts).
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

    private val _result = MutableStateFlow<ProcessResult?>(null)
    val result: StateFlow<ProcessResult?> = _result

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val uri = videoUri
        if (uri == null) {
            _state.value = PipelineUiState.Error("No video selected", PipelineStage.EXTRACT)
            return
        }
        job = viewModelScope.launch {
            var stage = PipelineStage.EXTRACT
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
                withContext(Dispatchers.Default) {
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
                }
                // ---- Pass 2: DETECT + EMBED ----
                stage = PipelineStage.DETECT
                val detector = MlKitFaceDetector()
                // Lazily created here so a missing model asset fails in
                // EMBED reporting, not before any progress is shown.
                val embedder = TfliteMobileFaceNet(extractor.appContext)
                val samples = mutableListOf<FaceSample>()
                try {
                    var framesWithFaces = 0
                    var facesTotal = 0
                    var whipDrops = 0
                    var blurredFaces = 0
                    var d = 0
                    val t1 = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Default) {
                        extractor.frames(uri).collect { frame ->
                            currentCoroutineContext().ensureActive()
                            d++
                            val bmp = frame.bitmap
                            if (BlurEstimator.sharpnessOf(bmp) < BlurEstimator.FRAME_MIN_VARIANCE) {
                                whipDrops++
                            } else {
                                // Embedding piggybacks on the DETECT stage row:
                                // detection (~25ms) dominates, embedding (~20ms)
                                // follows per face before recycle.
                                val faces = detector.detect(bmp).largest()
                                var kept = 0
                                for (f in faces) {
                                    val region = Rect(f.left, f.top, f.right, f.bottom)
                                    val sharp = BlurEstimator.sharpnessOf(bmp, region, 64)
                                    if (sharp < BlurEstimator.FACE_MIN_VARIANCE) {
                                        blurredFaces++
                                    } else {
                                        val crop = squareFaceCrop(
                                            bmp, f, rollDeg = f.eulerZ,
                                        )
                                        val emb = embedder.embed(crop)
                                        crop.recycle()
                                        samples.add(
                                            FaceSample(
                                                tsMs = frame.timestampMs,
                                                embedding = emb,
                                                sharpness = sharp,
                                                eulerY = f.eulerY,
                                                eulerZ = f.eulerZ,
                                                eyeOpen = min(
                                                    f.leftEyeOpen ?: 0.5f,
                                                    f.rightEyeOpen ?: 0.5f,
                                                ),
                                                smiling = f.smiling ?: 0.5f,
                                                edgeClipped = f.edgeClipped,
                                                area = f.area,
                                                trackingId = f.trackingId,
                                                left = f.left,
                                                top = f.top,
                                                right = f.right,
                                                bottom = f.bottom,
                                                frameW = bmp.width,
                                                frameH = bmp.height,
                                                soloFrame = faces.size == 1,
                                            ),
                                        )
                                        kept++
                                    }
                                }
                                if (kept > 0) framesWithFaces++
                                facesTotal += kept
                            }
                            _state.value = PipelineUiState.Running(
                                stage = PipelineStage.DETECT,
                                done = d,
                                total = total,
                                etaMs = eta(SystemClock.elapsedRealtime() - t1, d, total),
                            )
                            bmp.recycle()
                        }
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
                    embedder.close()
                }
                // ---- CLUSTER + SEGMENT (in-memory) ----
                stage = PipelineStage.CLUSTER
                _state.value = PipelineUiState.Running(
                    stage = PipelineStage.CLUSTER,
                    done = 0,
                    total = 1,
                    etaMs = null,
                )
                val people = withContext(Dispatchers.Default) {
                    Clusterer().cluster(samples) { msg -> Log.d("FaceCard", msg) }
                        .map { members ->
                            val best = QualityScorer.best(members)
                            Person(
                                id = -1, // assigned below after filtering
                                label = "",
                                samples = members,
                                appearances = AppearanceSegmenter.segment(members),
                                best = best,
                            )
                        }
                        .filter { it.appearances.isNotEmpty() }
                        .also { kept ->
                            // Faces not assigned to any shown person: DBSCAN
                            // noise, pruned singletons, or clusters with no
                            // countable (≥3-frame) appearance.
                            val stray = samples.size - kept.sumOf { it.samples.size }
                            Log.d("FaceCard", "kept ${kept.size} people ($stray stray faces)")
                        }
                        .mapIndexed { i, p -> p.copy(id = i, label = "Person ${'A' + i}") }
                }
                val result = ProcessResult(people)
                _result.value = result
                Log.d(
                    "FaceCard",
                    "result: ${result.personCount} people, " +
                        "${result.totalAppearances} appearances; " +
                        people.joinToString("; ") { p ->
                            "${p.label} n=${p.samples.size} " +
                                p.appearances.joinToString(",") {
                                    "[${it.startMs}-${it.endMs}]"
                                }
                        },
                )
                _state.value = PipelineUiState.Running(
                    stage = PipelineStage.CLUSTER,
                    done = 1,
                    total = 1,
                    etaMs = null,
                )
                // ---- SCORE: best-shot thumbs (full-res re-extract, generous crop) ----
                stage = PipelineStage.SCORE
                val thumbs = mutableMapOf<Int, Bitmap>()
                withContext(Dispatchers.Default) {
                    for ((i, p) in people.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        _state.value = PipelineUiState.Running(
                            stage = PipelineStage.SCORE,
                            done = i,
                            total = people.size.coerceAtLeast(1),
                            etaMs = null,
                        )
                        try {
                            extractor.frameAt(uri, p.best.tsMs)?.let { full ->
                                val crop = generousCrop(
                                    p.best, full.width, full.height,
                                    shared = !p.best.soloFrame,
                                )
                                val tile = Bitmap.createBitmap(
                                    full, crop.l, crop.t, crop.w, crop.h,
                                )
                                full.recycle()
                                val tw = 360
                                val th = (360f * tile.height / tile.width).toInt()
                                    .coerceAtLeast(1)
                                thumbs[p.id] = Bitmap.createScaledBitmap(tile, tw, th, true)
                                tile.recycle()
                            }
                        } catch (_: Exception) {
                            // A missing thumb must never fail the run;
                            // the collage renderer re-extracts independently.
                        }
                    }
                }
                _state.value = PipelineUiState.Running(
                    stage = PipelineStage.SCORE,
                    done = people.size.coerceAtLeast(1),
                    total = people.size.coerceAtLeast(1),
                    etaMs = null,
                )
                val doneMeta = meta.copy(frameCount = count)
                (extractor.appContext.applicationContext as FaceCardApp)
                    .resultStore.set(ProcessResult(people), thumbs, doneMeta, uri)
                _state.value = PipelineUiState.Done(doneMeta)
            } catch (e: CancellationException) {
                _state.value = PipelineUiState.Cancelled
                throw e
            } catch (e: Exception) {
                _state.value = PipelineUiState.Error(
                    e.message ?: "Processing failed",
                    stage,
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
