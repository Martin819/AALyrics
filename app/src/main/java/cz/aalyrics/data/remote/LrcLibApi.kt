package cz.aalyrics.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface LrcLibApi {
    @GET("/api/get")
    suspend fun get(
        @Query("artist_name") artist: String,
        @Query("track_name") track: String,
        @Query("album_name") album: String?,
        @Query("duration") durationSec: Int?,
    ): LrcLibTrack

    @GET("/api/search")
    suspend fun search(@Query("q") query: String): List<LrcLibTrack>
}

@JsonClass(generateAdapter = true)
data class LrcLibTrack(
    val id: Long?,
    val name: String?,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Double?,
    val instrumental: Boolean?,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)
