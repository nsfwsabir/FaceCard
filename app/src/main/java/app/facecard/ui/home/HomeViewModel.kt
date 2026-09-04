package app.facecard.ui.home

import androidx.lifecycle.ViewModel
import app.facecard.domain.pipeline.Clusterer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Phase 1 stub. Phase 2: recent SAF uris + metadata. Phase 4: threshold preset via DataStore. */
class HomeViewModel : ViewModel() {
    private val _thresholdLabel = MutableStateFlow("τ ${Clusterer.COSINE_THRESHOLD}")
    val thresholdLabel: StateFlow<String> = _thresholdLabel
}
