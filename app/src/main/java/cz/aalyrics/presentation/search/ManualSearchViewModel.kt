package cz.aalyrics.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.aalyrics.data.repository.LyricsRepository
import cz.aalyrics.domain.LyricsController
import cz.aalyrics.domain.model.LyricsResult
import cz.aalyrics.domain.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ManualSearchViewModel @Inject constructor(
    private val repo: LyricsRepository,
    private val controller: LyricsController,
) : ViewModel() {

    data class UiState(
        val artist: String = "",
        val title: String = "",
        val status: Status = Status.Idle,
        val lyricsPreview: String? = null,
    ) { enum class Status { Idle, Loading, Found, NotFound, Error } }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setArtist(v: String) { _state.value = _state.value.copy(artist = v) }
    fun setTitle(v: String) { _state.value = _state.value.copy(title = v) }

    fun search() {
        val s = _state.value
        if (s.artist.isBlank() || s.title.isBlank()) return
        _state.value = s.copy(status = UiState.Status.Loading, lyricsPreview = null)
        viewModelScope.launch {
            val song = Song(title = s.title.trim(), artist = s.artist.trim())
            when (val r = repo.fetch(song, preferSynced = true)) {
                is LyricsResult.Success -> {
                    controller.overrideLyrics(song, r.lyrics)
                    _state.value = _state.value.copy(
                        status = UiState.Status.Found,
                        lyricsPreview = r.lyrics.lines.joinToString("\n") { it.text },
                    )
                }
                LyricsResult.NotFound -> _state.value =
                    _state.value.copy(status = UiState.Status.NotFound)
                is LyricsResult.Error -> _state.value =
                    _state.value.copy(status = UiState.Status.Error)
                LyricsResult.Loading -> Unit
            }
        }
    }
}
