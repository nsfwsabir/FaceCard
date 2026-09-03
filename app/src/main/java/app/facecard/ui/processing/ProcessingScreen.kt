package app.facecard.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.facecard.ui.theme.FaceCardTheme

/**
 * Phase 1 placeholder. Phase 2 wires a real StateFlow<PipelineUiState>:
 * Extract → Detect → Embed → Cluster → Best shots → Collage, with
 * determinate progress, cancel, and rotation-safe ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Processing") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Extract frames…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = 0.2f, modifier = Modifier.fillMaxWidth())
            Text(
                "Pipeline UI lands in Phase 2. Stages: Extract → Detect → " +
                    "Embed → Cluster → Best shots → Collage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone) { Text("Simulate done →") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProcessingPreview() {
    FaceCardTheme(darkTheme = false, dynamicColor = false) {
        ProcessingScreen(onDone = {}, onCancel = {})
    }
}
