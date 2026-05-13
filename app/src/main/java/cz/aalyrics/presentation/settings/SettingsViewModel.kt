package cz.aalyrics.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.aalyrics.data.repository.LyricsRepository
import cz.aalyrics.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val lyrics: LyricsRepository,
) : ViewModel() {

    data class UiState(
        val offsetMs: Long = 0L,
        val preferSynced: Boolean = true,
    )

    val state = combine(settings.offsetMsFlow, settings.preferSyncedFlow) { o, p ->
        UiState(o, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    fun setOffset(value: Long) = viewModelScope.launch { settings.setOffsetMs(value) }
    fun setPreferSynced(v: Boolean) = viewModelScope.launch { settings.setPreferSynced(v) }
    fun clearCache() = viewModelScope.launch { lyrics.clearCache() }
}
