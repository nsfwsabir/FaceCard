package app.facecard.ui.collage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facecard.data.FaceCardApp
import app.facecard.data.video.MediaMetadataRetrieverFrameExtractor
import kotlinx.coroutines.launch

/**
 * Renders the collage bitmap (preview == export), with working
 * Save-to-gallery and system share-sheet actions.
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
        val saveState by vm.saveUi.collectAsStateWithLifecycle()
        LaunchedEffect(m.uri) { vm.render() }

        // Write permission only exists pre-29; SAF/MediaStore need none on 29+.
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                vm.save(context.applicationContext)
            } else {
                scope.launch {
                    snackbar.showSnackbar("Gallery permission denied — try Share instead.")
                }
            }
        }
        fun onSave() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                vm.save(context.applicationContext)
            }
        }
        LaunchedEffect(saveState) {
            when (val s = saveState) {
                is CollageViewModel.SaveUi.Saved ->
                    snackbar.showSnackbar("Saved to Pictures/FaceCard.")
                is CollageViewModel.SaveUi.Error ->
                    snackbar.showSnackbar("Save failed: ${s.message}")
                else -> Unit
            }
            if (saveState !is CollageViewModel.SaveUi.Idle &&
                saveState !is CollageViewModel.SaveUi.Saving
            ) {
                vm.acknowledgeSave()
            }
        }

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
                        Button(
                            onClick = ::onSave,
                            enabled = saveState !is CollageViewModel.SaveUi.Saving,
                        ) {
                            Text(
                                if (saveState is CollageViewModel.SaveUi.Saving) {
                                    "Saving…"
                                } else {
                                    "Save to gallery"
                                },
                            )
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val intent = vm.shareIntent(context.applicationContext)
                                if (intent != null) {
                                    context.startActivity(
                                        Intent.createChooser(intent, "Share collage"),
                                    )
                                } else {
                                    snackbar.showSnackbar("Render the collage first.")
                                }
                            }
                        }) { Text("Share") }
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
