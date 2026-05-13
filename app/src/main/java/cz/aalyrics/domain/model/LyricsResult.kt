package cz.aalyrics.domain.model

sealed interface LyricsResult {
    data object Loading : LyricsResult
    data object NotFound : LyricsResult
    data class Error(val message: String) : LyricsResult
    data class Success(val song: Song, val lyrics: SyncedLyrics) : LyricsResult
}
