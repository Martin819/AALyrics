package cz.aalyrics.domain.model

data class PlaybackState(
    val song: Song?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun currentPositionMs(): Long {
        if (!isPlaying || song == null) return positionMs
        val delta = System.currentTimeMillis() - updatedAt
        val pos = positionMs + delta
        return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
    }

    companion object {
        val Idle = PlaybackState(null, 0L, false, 0L)
    }
}
