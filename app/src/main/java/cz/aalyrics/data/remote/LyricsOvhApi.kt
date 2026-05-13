package cz.aalyrics.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path

interface LyricsOvhApi {
    @GET("/v1/{artist}/{title}")
    suspend fun get(
        @Path("artist") artist: String,
        @Path("title") title: String,
    ): LyricsOvhResponse
}

@JsonClass(generateAdapter = true)
data class LyricsOvhResponse(
    val lyrics: String?,
    val error: String?,
)
