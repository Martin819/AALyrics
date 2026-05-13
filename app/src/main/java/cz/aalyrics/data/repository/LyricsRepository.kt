package cz.aalyrics.data.repository

import cz.aalyrics.data.local.CachedLyricsEntity
import cz.aalyrics.data.local.LyricsDao
import cz.aalyrics.data.remote.LrcLibApi
import cz.aalyrics.data.remote.LyricsOvhApi
import cz.aalyrics.domain.lrc.LrcParser
import cz.aalyrics.domain.model.LyricsResult
import cz.aalyrics.domain.model.Song
import cz.aalyrics.domain.model.SyncedLyrics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsRepository @Inject constructor(
    private val lrcLib: LrcLibApi,
    private val lyricsOvh: LyricsOvhApi,
    private val dao: LyricsDao,
) {

    /**
     * Resolves lyrics for [song]:
     * 1. Cache lookup by `artist|title|duration_sec`.
     * 2. LRCLIB exact /api/get.
     * 3. LRCLIB /api/search fallback (best-by-title-match).
     * 4. Lyrics.ovh plain-text fallback.
     * Cache write happens whenever a non-empty result is obtained.
     */
    suspend fun fetch(song: Song, preferSynced: Boolean = true): LyricsResult {
        if (!song.isValid) return LyricsResult.NotFound
        val key = song.cacheKey()

        // 1. cache
        dao.findByKey(key)?.let { entity ->
            val parsed = chooseFromCache(entity, preferSynced)
            if (parsed.lines.isNotEmpty()) return LyricsResult.Success(song, parsed)
        }

        // 2. LRCLIB get
        val lrclibGet = runCatching {
            lrcLib.get(
                artist = song.artist,
                track = song.title,
                album = song.album,
                durationSec = if (song.durationMs > 0) (song.durationMs / 1000).toInt() else null,
            )
        }.getOrNull()

        var synced: String? = lrclibGet?.syncedLyrics?.takeIf { it.isNotBlank() }
        var plain: String? = lrclibGet?.plainLyrics?.takeIf { it.isNotBlank() }

        // 3. LRCLIB search fallback
        if (synced == null && plain == null) {
            val search = runCatching {
                lrcLib.search("${song.artist} ${song.title}")
            }.getOrNull().orEmpty()
            val best = search.firstOrNull {
                it.trackName.equalsLoose(song.title) && it.artistName.equalsLoose(song.artist)
            } ?: search.firstOrNull()
            synced = best?.syncedLyrics?.takeIf { it.isNotBlank() }
            plain = best?.plainLyrics?.takeIf { it.isNotBlank() }
        }

        // 4. Lyrics.ovh fallback (plain only)
        if (synced == null && plain == null) {
            val ovh = runCatching { lyricsOvh.get(song.artist, song.title) }.getOrNull()
            plain = ovh?.lyrics?.takeIf { it.isNotBlank() && ovh.error == null }
        }

        if (synced == null && plain == null) return LyricsResult.NotFound

        val lyrics = if (preferSynced && synced != null) {
            LrcParser.parse(synced, plain)
        } else if (plain != null) {
            LrcParser.parse(null, plain)
        } else {
            LrcParser.parse(synced, null)
        }

        // persist to cache
        runCatching {
            dao.upsert(
                CachedLyricsEntity(
                    cacheKey = key,
                    artist = song.artist,
                    title = song.title,
                    album = song.album,
                    durationMs = song.durationMs,
                    syncedLrc = synced,
                    plainText = plain,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return LyricsResult.Success(song, lyrics)
    }

    suspend fun saveManual(song: Song, lyrics: SyncedLyrics, rawSynced: String?, rawPlain: String?) {
        dao.upsert(
            CachedLyricsEntity(
                cacheKey = song.cacheKey(),
                artist = song.artist,
                title = song.title,
                album = song.album,
                durationMs = song.durationMs,
                syncedLrc = rawSynced,
                plainText = rawPlain ?: lyrics.plainText,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearCache() = dao.clearAll()
    suspend fun cacheSize() = dao.count()

    private fun chooseFromCache(entity: CachedLyricsEntity, preferSynced: Boolean): SyncedLyrics {
        return if (preferSynced && !entity.syncedLrc.isNullOrBlank()) {
            LrcParser.parse(entity.syncedLrc, entity.plainText)
        } else if (!entity.plainText.isNullOrBlank()) {
            LrcParser.parse(null, entity.plainText)
        } else {
            LrcParser.parse(entity.syncedLrc, null)
        }
    }

    private fun String?.equalsLoose(other: String): Boolean =
        this?.trim()?.equals(other.trim(), ignoreCase = true) == true
}
