package cz.aalyrics.domain.usecase

import cz.aalyrics.data.repository.LyricsRepository
import cz.aalyrics.domain.model.LyricsResult
import cz.aalyrics.domain.model.Song
import javax.inject.Inject

class FetchLyricsUseCase @Inject constructor(
    private val repo: LyricsRepository,
) {
    suspend operator fun invoke(song: Song, preferSynced: Boolean): LyricsResult =
        repo.fetch(song, preferSynced)
}
