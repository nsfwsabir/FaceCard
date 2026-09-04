package app.facecard.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Phase 1 stub. Phase 2: recent SAF uris + metadata. Phase 4: threshold preset via DataStore. */
class HomeViewModel : ViewModel() {
    private val _thresholdLabel = MutableStateFlow("τ 0.55")
    val thresholdLabel: StateFlow<String> = _thresholdLabel
}
