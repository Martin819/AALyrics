package cz.aalyrics.domain

import cz.aalyrics.data.repository.LyricsRepository
import cz.aalyrics.data.repository.MediaSessionRepository
import cz.aalyrics.data.repository.SettingsRepository
import cz.aalyrics.domain.lrc.LyricsSyncCalculator
import cz.aalyrics.domain.model.LyricsResult
import cz.aalyrics.domain.model.PlaybackState
import cz.aalyrics.domain.model.Song
import cz.aalyrics.domain.model.SyncedLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * Owns the active-song → lyrics → active-line pipeline. A single instance is
 * shared by the foreground service, the Compose UI and the Android Auto
 * CarAppService so they all observe the SAME state.
 */
@Singleton
class LyricsController @Inject constructor(
    val mediaSession: MediaSessionRepository,
    private val lyricsRepo: LyricsRepository,
    private val settings: SettingsRepository,
) {

    data class UiState(
        val playback: PlaybackState = PlaybackState.Idle,
        val lyrics: SyncedLyrics = SyncedLyrics.Empty,
        val activeLineIndex: Int = -1,
        val status: Status = Status.Idle,
        val manualOffsetMs: Long = 0L,
    ) {
        enum class Status { Idle, Recognising, LoadingLyrics, Ready, NotFound, Error }

        val activeLine: String?
            get() = lyrics.lines.getOrNull(activeLineIndex)?.text?.takeIf { it.isNotBlank() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var ticker: Job? = null
    private var fetchJob: Job? = null
    private var lastKey: String? = null
    private var savedOffsetMs: Long = 0L
    private var preferSynced: Boolean = true

    fun start() {
        mediaSession.start()

        scope.launch {
            combine(
                settings.offsetMsFlow,
                settings.preferSyncedFlow,
            ) { off, pref -> off to pref }
                .distinctUntilChanged()
                .collect { (off, pref) ->
                    savedOffsetMs = off
                    preferSynced = pref
                    recomputeActiveLine()
                }
        }

        mediaSession.state
            .onEach { onPlayback(it) }
            .launchIn(scope)

        startTicker()
    }

    fun stop() {
        ticker?.cancel()
        fetchJob?.cancel()
        scope.cancel()
        mediaSession.stop()
    }

    fun bumpOffset(deltaMs: Long) {
        scope.launch { settings.setOffsetMs(savedOffsetMs + deltaMs) }
    }

    fun overrideLyrics(song: Song, lyrics: SyncedLyrics) {
        _state.value = _state.value.copy(
            playback = _state.value.playback.copy(song = song),
            lyrics = lyrics,
            status = if (lyrics.lines.isEmpty()) UiState.Status.NotFound else UiState.Status.Ready,
        )
        recomputeActiveLine()
    }

    private fun onPlayback(pb: PlaybackState) {
        val cur = _state.value
        val key = pb.song?.cacheKey()
        if (key != lastKey) {
            lastKey = key
            _state.value = cur.copy(
                playback = pb,
                lyrics = SyncedLyrics.Empty,
                activeLineIndex = -1,
                status = if (pb.song == null) UiState.Status.Idle else UiState.Status.LoadingLyrics,
            )
            pb.song?.let { fetchFor(it) }
        } else {
            _state.value = cur.copy(playback = pb)
            recomputeActiveLine()
        }
    }

    private fun fetchFor(song: Song) {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            _state.value = _state.value.copy(status = UiState.Status.LoadingLyrics)
            when (val r = lyricsRepo.fetch(song, preferSynced)) {
                is LyricsResult.Success -> _state.value = _state.value.copy(
                    lyrics = r.lyrics,
                    status = UiState.Status.Ready,
                )
                LyricsResult.NotFound -> _state.value = _state.value.copy(
                    lyrics = SyncedLyrics.Empty,
                    status = UiState.Status.NotFound,
                )
                is LyricsResult.Error -> _state.value = _state.value.copy(
                    status = UiState.Status.Error,
                )
                LyricsResult.Loading -> Unit
            }
            recomputeActiveLine()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                recomputeActiveLine()
                delay(150L)
            }
        }
    }

    private fun recomputeActiveLine() {
        val cur = _state.value
        if (cur.lyrics.lines.isEmpty()) {
            if (cur.activeLineIndex != -1) _state.value = cur.copy(activeLineIndex = -1)
            return
        }
        val pos = cur.playback.currentPositionMs()
        val dur = cur.playback.durationMs
        val idx = LyricsSyncCalculator.activeLineIndex(cur.lyrics, pos, dur, savedOffsetMs)
        if (idx != cur.activeLineIndex) {
            _state.value = cur.copy(activeLineIndex = idx)
        }
    }
}
