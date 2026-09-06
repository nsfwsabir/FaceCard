package app.facecard.ui.processing

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facecard.data.video.MediaMetadataRetrieverFrameExtractor
import app.facecard.domain.model.ProcessResult
import app.facecard.domain.model.VideoMeta
import app.facecard.domain.pipeline.PipelineStage
import app.facecard.domain.pipeline.PipelineUiState
import app.facecard.ui.theme.FaceCardTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    videoUri: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(videoUri) {
        videoUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    val vm: ProcessingViewModel = viewModel(
        key = videoUri,
        factory = ProcessingViewModelFactory(
            uri,
            MediaMetadataRetrieverFrameExtractor(context.applicationContext),
        ),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val thumbnail by vm.thumbnail.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()

    LaunchedEffect(videoUri) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Processing") },
                actions = {
                    if (state is PipelineUiState.Running) {
                        TextButton(onClick = {
                            vm.cancel()
                            onCancel()
                        }) { Text("Cancel") }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                PipelineUiState.Idle -> Text("Preparing…")
                is PipelineUiState.Running -> RunningBody(s)
                is PipelineUiState.Done -> DoneBody(
                    meta = s.meta,
                    thumbnail = thumbnail,
                    stats = stats,
                    result = result,
                    onContinue = onDone,
                    onRetry = { vm.start() },
                )
                is PipelineUiState.Error -> ErrorBody(
                    message = s.message,
                    onRetry = { vm.start() },
                    onBack = onCancel,
                )
                PipelineUiState.Cancelled -> ErrorBody(
                    message = "Processing cancelled.",
                    onRetry = { vm.start() },
                    onBack = onCancel,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.RunningBody(s: PipelineUiState.Running) {
    Text(s.stage.label, style = MaterialTheme.typography.titleLarge)
    LinearProgressIndicator(
        progress = s.overall,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = buildString {
            append("${(s.overall * 100).toInt()}%")
            if (s.stage == PipelineStage.EXTRACT) append(" · frame ${s.done}/${s.total}")
            s.etaMs?.let { append(" · ~${it / 1000}s left") }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    PipelineStage.entries.forEach { stage ->
        StageRow(
            label = stage.label,
            state = when {
                stage.ordinal < s.stage.ordinal -> StageState.DONE
                stage == s.stage -> StageState.CURRENT
                else -> StageState.PENDING
            },
            detail = if (stage == s.stage) "${s.done}/${s.total}" else null,
        )
    }
    Spacer(Modifier.weight(1f))
    Text(
        "Runs off the main thread — the app stays responsive. " +
            "Rotation won't lose progress.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class StageState { DONE, CURRENT, PENDING }

@Composable
private fun StageRow(label: String, state: StageState, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            StageState.DONE -> Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "$label done",
                tint = MaterialTheme.colorScheme.primary,
            )
            StageState.CURRENT -> Icon(
                Icons.Rounded.RadioButtonUnchecked,
                contentDescription = "$label in progress",
                tint = MaterialTheme.colorScheme.primary,
            )
            StageState.PENDING -> Icon(
                Icons.Rounded.RadioButtonUnchecked,
                contentDescription = "$label pending",
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state == StageState.PENDING) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DoneBody(
    meta: VideoMeta,
    thumbnail: android.graphics.Bitmap?,
    stats: DetectStats?,
    result: ProcessResult?,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "First frame",
                    modifier = Modifier
                        .size(72.dp, 128.dp)
                        .clip(MaterialTheme.shapes.large),
                )
            } else {
                Icon(Icons.Rounded.Cancel, contentDescription = null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(meta.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${meta.frameCount} frames · ${"%.1f".format(meta.durationMs / 1000f)}s" +
                        if (meta.width > 0) " · ${meta.width}×${meta.height}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result != null) {
                    Text(
                        "${result.personCount} people · " +
                            "${result.totalAppearances} appearances",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    Text(
        stats?.let {
            "Detection: ${it.facesTotal} faces in ${it.framesWithFaces}/${it.frames} frames · " +
                "${it.whipPanDrops} whip-pan drops · ${it.blurredFaceDrops} blurry-face drops · " +
                "${it.partialFaces} partial faces skipped."
        } ?: "Extraction works. Face detection plugs into this stream in Phase 3.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onContinue) { Text("Continue →") }
    OutlinedButton(onClick = onRetry) { Text("Process again") }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
    Text(message, color = MaterialTheme.colorScheme.error)
    Button(onClick = onRetry) { Text("Retry") }
    OutlinedButton(onClick = onBack) { Text("Pick another video") }
}

@Preview(showBackground = true)
@Composable
private fun ProcessingPreview() {
    FaceCardTheme(darkTheme = false, dynamicColor = false) {
        Column(Modifier.padding(20.dp)) {
            RunningBody(
                PipelineUiState.Running(PipelineStage.EXTRACT, 98, 150, 12_000),
            )
        }
    }
}
