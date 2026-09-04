package app.facecard.domain.pipeline

import app.facecard.domain.model.VideoMeta

/**
 * Pipeline stages in execution order (TRD §3). Pure Kotlin — no Android deps.
 */
enum class PipelineStage(val label: String) {
    EXTRACT("Extract frames"),
    DETECT("Detect faces"),
    EMBED("Embed faces"),
    CLUSTER("Cluster people"),
    SCORE("Pick best shots"),
    COLLAGE("Build collage"),
}

sealed interface PipelineUiState {
    data object Idle : PipelineUiState

    data class Running(
        val stage: PipelineStage,
        val done: Int,
        val total: Int,
        val etaMs: Long?,
    ) : PipelineUiState {
        val fraction: Float
            get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        val overall: Float
            get() = PipelineProgress.overall(stage, fraction)
    }

    /** Thumbnail is exposed separately (ViewModel) to keep domain Bitmap-free. */
    data class Done(val meta: VideoMeta) : PipelineUiState

    data class Error(val message: String, val failedStage: PipelineStage) : PipelineUiState

    data object Cancelled : PipelineUiState
}

/**
 * Maps per-stage fractions to one determinate bar (TRD progress mapping):
 * Extract 0–20 · Detect 20–55 · Embed 55–70 · Cluster 70–80 · Score 80–90 · Collage 90–100.
 */
object PipelineProgress {
    private val weights: Map<PipelineStage, Float> = mapOf(
        PipelineStage.EXTRACT to 0.20f,
        PipelineStage.DETECT to 0.35f,
        PipelineStage.EMBED to 0.15f,
        PipelineStage.CLUSTER to 0.10f,
        PipelineStage.SCORE to 0.10f,
        PipelineStage.COLLAGE to 0.10f,
    )

    fun overall(stage: PipelineStage, fraction: Float): Float {
        var acc = 0f
        for (s in PipelineStage.entries) {
            val w = weights.getValue(s)
            if (s.ordinal < stage.ordinal) {
                acc += w
            } else if (s == stage) {
                acc += w * fraction.coerceIn(0f, 1f)
                break
            } else {
                break
            }
        }
        return acc.coerceIn(0f, 1f)
    }
}
