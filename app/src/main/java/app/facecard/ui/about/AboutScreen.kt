package app.facecard.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.facecard.data.FaceCardApp
import app.facecard.domain.pipeline.AppearanceSegmenter
import app.facecard.domain.pipeline.Clusterer
import kotlinx.coroutines.launch

/**
 * In-app documentation: model, thresholds, licences, cache controls.
 * Mirrors README.md — both must agree on the model name and τ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FaceCardApp
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("About FaceCard") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("facecard", style = MaterialTheme.typography.displaySmall)
            Text(
                "Every face, once. Your video's best story.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AboutCard("Pipeline (100% on-device)") {
                Text("1. Extract frames @5fps (MediaMetadataRetriever)")
                Text("2. Detect faces (ML Kit, bundled model)")
                Text("3. Embed (MobileFaceNet 192-d TFLite) + Laplacian blur gate")
                Text("4. Cluster (cosine τ = ${Clusterer.COSINE_THRESHOLD}) + segment")
                Text("5. Best-shot scoring + 1080×1920 story collage")
            }
            AboutCard("Embedding model") {
                Text("MobileFaceNet, 112×112 → 192-d, float32")
                Text("Bundled: assets/mobilefacenet.tflite")
                Text("Via MCarlomagno/FaceRecognitionAuth (BSD-3-Clause)")
            }
            AboutCard("Key thresholds") {
                Text("Smile DBSCAN: cosine eps 0.45 · " +
                    "minPts ${Clusterer.DBSCAN_MIN_PTS} (LGPL-3.0, on-device)")
                Text("Join operating point: cosine sim τ = ${Clusterer.COSINE_THRESHOLD}")
                Text("Blur: frame ≥ 40, face ≥ 60 (Laplacian variance)")
                Text("Segment gap ≤ ${AppearanceSegmenter.GAP_TOL_MS}ms, " +
                    "min length ${AppearanceSegmenter.MIN_SEGMENT_LEN} frames")
            }
            AboutCard("Storage & privacy") {
                Text("Frames and embeddings stay in app cache; nothing leaves " +
                    "the device. Collages save to Pictures/FaceCard only on tap.")
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            context.cacheDir.deleteRecursively()
                            app.resultStore.clear()
                        }
                        snackbar.showSnackbar("Cache cleared.")
                    }
                }) { Text("Clear cache") }
            }
            Text(
                "FaceCard 1.0 · minSdk 26 · Kotlin + Compose + ML Kit + TFLite",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, body: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body()
        }
    }
}
