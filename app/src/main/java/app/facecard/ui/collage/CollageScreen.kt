package app.facecard.ui.collage

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facecard.data.FaceCardApp
import app.facecard.data.video.MediaMetadataRetrieverFrameExtractor
import kotlinx.coroutines.launch

/**
 * Phase 5: renders the real collage bitmap and previews it.
 * The SAME bitmap is written to disk on save (Phase 6) — parity guaranteed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FaceCardApp
    val result by app.resultStore.result.collectAsState()
    val meta by app.resultStore.meta.collectAsState()
    val uri by app.resultStore.videoUri.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val soon: () -> Unit = {
        scope.launch { snackbar.showSnackbar("Save & Share land in Phase 6.") }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Collage") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val res = result
        val m = meta
        val u = uri
        if (res == null || m == null || u == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No collage yet", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Process a video first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            return@Scaffold
        }
        val vm: CollageViewModel = viewModel(
            key = "collage-${m.uri}",
            factory = CollageViewModelFactory(
                u, m, res,
                MediaMetadataRetrieverFrameExtractor(context.applicationContext),
            ),
        )
        val uiState by vm.ui.collectAsStateWithLifecycle()
        LaunchedEffect(m.uri) { vm.render() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "facecard · ${m.displayName}",
                style = MaterialTheme.typography.headlineSmall,
            )
            when (val s = uiState) {
                CollageViewModel.Ui.Idle,
                CollageViewModel.Ui.Rendering,
                -> {
                    CircularProgressIndicator()
                    Text(
                        "Composing your story…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is CollageViewModel.Ui.Done -> {
                    Image(
                        bitmap = s.bitmap.asImageBitmap(),
                        contentDescription = "Story collage: ${res.personCount} people",
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .aspectRatio(9f / 16f)
                            .clip(MaterialTheme.shapes.large),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = soon) { Text("Save to gallery") }
                        OutlinedButton(onClick = soon) { Text("Share") }
                    }
                    Text(
                        "Preview = export (same renderer).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is CollageViewModel.Ui.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.render() }) { Text("Retry") }
                }
            }
        }
    }
}
