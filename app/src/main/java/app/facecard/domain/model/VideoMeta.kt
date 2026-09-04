package app.facecard.domain.model

/**
 * Metadata for a picked video. Pure Kotlin (no Android types) so the
 * domain layer stays unit-testable. Phase 2 populates all fields;
 * [frameCount] is filled after extraction finishes.
 */
data class VideoMeta(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameCount: Int = 0,
)
