package app.facecard.ui.collage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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

/** Phase 1 placeholder. Phase 5 builds the 9:16 preview; Phase 6 adds Save/Share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Collage") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("facecard · Sample 1", style = MaterialTheme.typography.headlineSmall)
            Text(
                "9:16 story preview (same renderer as export) plus " +
                    "Save to gallery + system share sheet land in Phase 5–6.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CollagePreview() {
    FaceCardTheme(darkTheme = false, dynamicColor = false) {
        CollageScreen(onBack = {})
    }
}
