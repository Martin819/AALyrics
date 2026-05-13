package cz.aalyrics.presentation.karaoke

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.aalyrics.domain.LyricsController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class KaraokeViewModel @Inject constructor(
    val controller: LyricsController,
) : ViewModel() {
    init { controller.start() }

    val state: StateFlow<LyricsController.UiState> = controller.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), controller.state.value)

    fun nudge(deltaMs: Long) = controller.bumpOffset(deltaMs)
}
