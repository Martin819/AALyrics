package cz.aalyrics.domain.model

data class LyricsLine(
    val timestampMs: Long,
    val text: String,
)

data class SyncedLyrics(
    val lines: List<LyricsLine>,
    val isSynced: Boolean,
    val plainText: String? = null,
) {
    companion object {
        val Empty = SyncedLyrics(emptyList(), false, null)
    }
}
