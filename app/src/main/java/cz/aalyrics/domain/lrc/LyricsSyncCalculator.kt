package cz.aalyrics.domain.lrc

import cz.aalyrics.domain.model.SyncedLyrics

/**
 * Resolves the active lyric line for a given playback position.
 * For synced lyrics: returns the last line whose timestamp <= position.
 * For unsynced lyrics: distributes lines uniformly across track duration.
 */
object LyricsSyncCalculator {

    fun activeLineIndex(lyrics: SyncedLyrics, positionMs: Long, durationMs: Long, offsetMs: Long = 0L): Int {
        if (lyrics.lines.isEmpty()) return -1
        val adjusted = positionMs + offsetMs
        return if (lyrics.isSynced) {
            // binary search: greatest index where ts <= adjusted
            var lo = 0
            var hi = lyrics.lines.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lyrics.lines[mid].timestampMs <= adjusted) {
                    ans = mid; lo = mid + 1
                } else hi = mid - 1
            }
            ans
        } else {
            if (durationMs <= 0L) return 0
            val ratio = (adjusted.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
            (ratio * lyrics.lines.size).toInt().coerceIn(0, lyrics.lines.lastIndex)
        }
    }
}
