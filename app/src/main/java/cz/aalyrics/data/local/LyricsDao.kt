package cz.aalyrics.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsDao {
    @Query("SELECT * FROM cached_lyrics WHERE cacheKey = :key LIMIT 1")
    suspend fun findByKey(key: String): CachedLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedLyricsEntity)

    @Query("DELETE FROM cached_lyrics")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM cached_lyrics")
    suspend fun count(): Int
}
