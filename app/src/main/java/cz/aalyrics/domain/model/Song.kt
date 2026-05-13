package cz.aalyrics.domain.model

data class Song(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val albumArtUri: String? = null,
    val sourcePackage: String? = null,
) {
    fun cacheKey(): String = "${artist.trim().lowercase()}|${title.trim().lowercase()}|${durationMs / 1000}"

    val isValid: Boolean get() = title.isNotBlank() && artist.isNotBlank()
}
