package cz.aalyrics.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_lyrics")
data class CachedLyricsEntity(
    @PrimaryKey val cacheKey: String,
    val artist: String,
    val title: String,
    val album: String?,
    val durationMs: Long,
    val syncedLrc: String?,
    val plainText: String?,
    val createdAt: Long,
)
