package app.facecard.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facecard.R
import app.facecard.ui.theme.FaceCardTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartDemo: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val threshold by vm.thresholdLabel.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("facecard") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "facecard",
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onStartDemo) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null)
                        Text(stringResource(R.string.choose_video))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("On-device") })
                AssistChip(onClick = {}, label = { Text("ML Kit + MobileFaceNet") })
                AssistChip(onClick = {}, label = { Text(threshold) })
            }

            Text(
                text = "Samples",
                style = MaterialTheme.typography.titleMedium,
            )
            // Phase 2 replaces with real thumbnails (Coil) + durations.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(3) { i ->
                    Card(
                        onClick = onStartDemo,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Icon(Icons.Rounded.Face, contentDescription = null)
                            Spacer(Modifier.height(8.dp))
                            Text("Sample ${i + 1}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "30s portrait",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("How counting works", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "One continuous clearly-visible segment = one appearance. " +
                            "Blurred whip-pan passes count for nobody.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "100% on-device · no uploads",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    FaceCardTheme(darkTheme = false, dynamicColor = false) {
        HomeScreen(onStartDemo = {})
    }
}
