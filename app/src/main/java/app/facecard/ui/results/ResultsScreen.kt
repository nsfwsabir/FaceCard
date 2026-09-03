package app.facecard.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

/** Phase 1 placeholder. Phase 5 builds person cards + appearance timelines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    onViewCollage: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Results") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("5 people · 20 appearances", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Person cards with best shots, counts, and expandable " +
                    "appearance timelines land in Phase 5.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onViewCollage) { Text("View collage") }
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ResultsPreview() {
    FaceCardTheme(darkTheme = false, dynamicColor = false) {
        ResultsScreen(onViewCollage = {}, onBack = {})
    }
}
