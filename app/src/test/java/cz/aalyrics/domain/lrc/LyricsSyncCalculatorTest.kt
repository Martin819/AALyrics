package cz.aalyrics.domain.lrc

import com.google.common.truth.Truth.assertThat
import cz.aalyrics.domain.model.LyricsLine
import cz.aalyrics.domain.model.SyncedLyrics
import org.junit.Test

class LyricsSyncCalculatorTest {
    private val synced = SyncedLyrics(
        lines = listOf(
            LyricsLine(0, "a"),
            LyricsLine(1000, "b"),
            LyricsLine(2000, "c"),
            LyricsLine(3000, "d"),
        ),
        isSynced = true,
    )

    @Test fun `picks last line whose timestamp is at or below position`() {
        assertThat(LyricsSyncCalculator.activeLineIndex(synced, 0, 4000)).isEqualTo(0)
        assertThat(LyricsSyncCalculator.activeLineIndex(synced, 1500, 4000)).isEqualTo(1)
        assertThat(LyricsSyncCalculator.activeLineIndex(synced, 2000, 4000)).isEqualTo(2)
        assertThat(LyricsSyncCalculator.activeLineIndex(synced, 9999, 4000)).isEqualTo(3)
    }

    @Test fun `negative position before first ts returns -1`() {
        assertThat(LyricsSyncCalculator.activeLineIndex(synced, -1, 4000)).isEqualTo(-1)
    }

    @Test fun `offset shifts active line`() {
        // position 800 + offset 300 = 1100 → second line
        assertThat(LyricsSyncCalculator.activeLineIndex(synced, 800, 4000, offsetMs = 300))
            .isEqualTo(1)
    }

    @Test fun `unsynced lyrics distribute uniformly over duration`() {
        val unsynced = SyncedLyrics(
            lines = (0 until 10).map { LyricsLine(0, "$it") },
            isSynced = false,
        )
        assertThat(LyricsSyncCalculator.activeLineIndex(unsynced, 5000, 10_000)).isEqualTo(5)
        assertThat(LyricsSyncCalculator.activeLineIndex(unsynced, 0, 10_000)).isEqualTo(0)
        assertThat(LyricsSyncCalculator.activeLineIndex(unsynced, 10_000, 10_000)).isEqualTo(9)
    }
}
