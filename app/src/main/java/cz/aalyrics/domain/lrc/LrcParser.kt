package cz.aalyrics.domain.lrc

import cz.aalyrics.domain.model.LyricsLine
import cz.aalyrics.domain.model.SyncedLyrics

/**
 * Parses LRC-formatted lyrics.
 *
 * Format:
 *   [mm:ss.xx] text
 *   [mm:ss.xxx] text
 *
 * Rules:
 *  - Metadata tags ([ti:], [ar:], [al:], [length:], [by:], [offset:]) are ignored
 *    EXCEPT [offset:] which is applied to every timestamp.
 *  - A single line may carry several timestamps — each emits a separate LyricsLine
 *    with the same text. Lines are sorted by timestamp.
 *  - If no valid timestamps are found, lyrics are returned as plain text.
 */
object LrcParser {

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})([.:](\d{1,3}))?]""")
    private val OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val METADATA_REGEX = Regex("""\[([a-zA-Z]+):[^]]*]""")

    fun parse(raw: String?, plainTextFallback: String? = null): SyncedLyrics {
        if (raw.isNullOrBlank()) {
            return if (!plainTextFallback.isNullOrBlank()) plainOnly(plainTextFallback)
            else SyncedLyrics.Empty
        }
        val offset = OFFSET_REGEX.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricsLine>()
        raw.lineSequence().forEach lines@{ line ->
            val stamps = TIMESTAMP_REGEX.findAll(line).toList()
            if (stamps.isEmpty()) return@lines
            // Strip BOTH metadata tags AND timestamps to obtain the pure lyric text.
            val text = line
                .replace(METADATA_REGEX, "")
                .replace(TIMESTAMP_REGEX, "")
                .trim()
            stamps.forEach stamp@{ m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@stamp
                val sec = m.groupValues[2].toLongOrNull() ?: return@stamp
                val fracRaw = m.groupValues[4]
                val frac = when {
                    fracRaw.isEmpty() -> 0L
                    fracRaw.length == 1 -> fracRaw.toLong() * 100
                    fracRaw.length == 2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                val ts = (min * 60_000L) + (sec * 1000L) + frac + offset
                out += LyricsLine(ts.coerceAtLeast(0L), text)
            }
        }
        if (out.isEmpty()) {
            return if (!plainTextFallback.isNullOrBlank()) plainOnly(plainTextFallback)
            else plainOnly(stripAllTags(raw))
        }
        val sorted = out.sortedBy { it.timestampMs }
        return SyncedLyrics(sorted, isSynced = true, plainText = null)
    }

    private fun plainOnly(text: String): SyncedLyrics {
        val cleaned = stripAllTags(text).trim()
        if (cleaned.isBlank()) return SyncedLyrics.Empty
        val lines = cleaned.lines().map { LyricsLine(0L, it) }
        return SyncedLyrics(lines, isSynced = false, plainText = cleaned)
    }

    private fun stripAllTags(s: String): String = s.replace(METADATA_REGEX, "").replace(TIMESTAMP_REGEX, "")
}
